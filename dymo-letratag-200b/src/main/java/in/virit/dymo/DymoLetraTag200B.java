package in.virit.dymo;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A Vaadin component that connects to a Dymo LetraTag 200B label printer
 * via the Web Bluetooth API and prints images.
 * <p>
 * The component is non-visual — add it to any layout and call
 * {@link #requestConnection()} to trigger the browser's Bluetooth
 * pairing dialog, then {@link #print(BufferedImage)} to send an image.
 * <p>
 * Uses the Dymo LetraTag 200B Bluetooth protocol with proper chunking and UUIDs.
 */
@Tag("dymo-letratag-200b")
public class DymoLetraTag200B extends Component implements in.virit.ble.LabelPrinter {

    private static final Logger LOG = Logger.getLogger(DymoLetraTag200B.class.getName());
    // Dymo LetraTag 200B specific UUIDs
    private static final String BLE_SERVICE_UUID = "be3dd650-2b3d-42f1-99c1-f0f749dd0678";
    private static final String BLE_WRITE_CHAR_UUID = "be3dd651-2b3d-42f1-99c1-f0f749dd0678";
    private static final String BLE_NOTIFY_CHAR_UUID = "be3dd652-2b3d-42f1-99c1-f0f749dd0678";
    private static final int CHUNK_SIZE = 500; // Dymo protocol uses 500 byte chunks

    private boolean connected;
    private final List<Consumer<Boolean>> connectionListeners = new ArrayList<>();

    public DymoLetraTag200B() {
        getElement().getStyle().set("display", "none");
        initJs();
    }

    /**
     * Opens the browser's Bluetooth pairing dialog to connect to a Dymo LetraTag 200B printer.
     */
    public void requestConnection() {
        getElement().executeJs("return this._dymo.connect()");
    }

    /**
     * Prints the given image on the connected Dymo LetraTag 200B printer.
     * The image is converted to monochrome and sent using the printer's protocol.
     *
     * @param image the image to print
     */
    public void print(BufferedImage image) {
        LOG.info("print() called: image " + image.getWidth() + "x" + image.getHeight()
                + " type=" + image.getType());
        byte[] commandBytes = buildPrintCommands(image);
        LOG.info("Built print commands: " + commandBytes.length + " bytes total");
        String base64 = Base64.getEncoder().encodeToString(commandBytes);
        getElement().executeJs("return this._dymo.sendData($0)", base64);
    }

    public boolean isConnected() {
        return connected;
    }

    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }

    @ClientCallable
    private void onConnectionChange(boolean connected) {
        this.connected = connected;
        connectionListeners.forEach(l -> l.accept(connected));
    }

    /**
     * Disconnects from the printer.
     */
    public void disconnect() {
        getElement().executeJs("this._dymo.disconnect()");
    }

    /**
     * Sends a simple test pattern to verify printer functionality.
     * This can help diagnose if the issue is with the image format vs printer hardware.
     */
    public void printTestPattern() {
        LOG.info("printTestPattern() called");
        
        // Create a simple 100x30 test pattern
        BufferedImage testImage = new BufferedImage(100, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = testImage.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 30);
        g.setColor(Color.BLACK);
        g.drawRect(0, 0, 99, 29);
        g.drawString("TEST", 10, 20);
        g.dispose();
        
        print(testImage);
    }

    private void initJs() {
        getElement().executeJs("""
            const el = this;
            const SERVICE = '%s';
            const WRITE_CHAR = '%s';
            const NOTIFY_CHAR = '%s';
            const L = (msg) => console.log('Dymo LT200B: ' + msg);
            const W = (msg) => console.warn('Dymo LT200B: ' + msg);
            const E = (msg) => console.error('Dymo LT200B: ' + msg);
            const hex = (arr) => Array.from(arr).map(b => b.toString(16).padStart(2, '0')).join(' ');
            el._dymo = {
              device: null,
              server: null,
              writeChar: null,
              notifyChar: null,
              async _setupDevice(device) {
                el._dymo.device = device;
                L('device: ' + device.name + ' id: ' + device.id);

                device.addEventListener('gattserverdisconnected', () => {
                  W('device disconnected');
                  el.$server.onConnectionChange(false);
                });

                L('connecting GATT server...');
                const server = await device.gatt.connect();
                el._dymo.server = server;
                L('GATT connected: ' + server.connected);

                const service = await server.getPrimaryService(SERVICE);
                L('got service: ' + service.uuid);

                const chars = await service.getCharacteristics();
                L('characteristics: ' + chars.length);
                for (const c of chars) {
                  const props = c.properties;
                  const propList = [];
                  if (props.read) propList.push('read');
                  if (props.write) propList.push('write');
                  if (props.writeWithoutResponse) propList.push('writeWithoutResponse');
                  if (props.notify) propList.push('notify');
                  if (props.indicate) propList.push('indicate');
                  L('  ' + c.uuid + ' props: ' + propList.join(', '));
                }

                el._dymo.writeChar = await service.getCharacteristic(WRITE_CHAR);
                L('write char props: write=' + el._dymo.writeChar.properties.write +
                  ' writeWithoutResponse=' + el._dymo.writeChar.properties.writeWithoutResponse);
                el._dymo.notifyChar = await service.getCharacteristic(NOTIFY_CHAR);

                try {
                  await el._dymo.notifyChar.startNotifications();
                  el._dymo.notifyChar.addEventListener('characteristicvaluechanged', (event) => {
                    const value = new Uint8Array(event.target.value.buffer);
                    L('notification: ' + hex(value) + ' (' + value.length + ' bytes)');
                    if (value.length >= 3 && value[0] === 0x1B && value[1] === 0x52) {
                      const status = value[2];
                      const msgs = {
                        0: 'Printing in progress',
                        1: 'Printing completed',
                        2: 'Failed (unknown reason)',
                        3: 'Printed but battery low',
                        4: 'Failed (cancelled)',
                        5: 'Failed (unknown reason)',
                        6: 'Failed (battery low)',
                        7: 'Failed (no cassette)'
                      };
                      L('print status: ' + (msgs[status] || 'Unknown status ' + status));
                    }
                  });
                  L('subscribed to notifications');
                } catch(e) {
                  W('failed to subscribe to notifications: ' + e.message);
                }

                L('connection complete. Ready to print.');
                el.$server.onConnectionChange(true);
              },
              async connect() {
                L('requesting device...');
                const device = await navigator.bluetooth.requestDevice({
                  filters: [{namePrefix: 'Letratag'}],
                  optionalServices: [SERVICE]
                });
                await el._dymo._setupDevice(device);
              },
              async sendData(base64) {
                const wc = el._dymo.writeChar;
                if (!wc) { E('not connected'); return; }
                const raw = atob(base64);
                const bytes = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);

                L('total command: ' + bytes.length + ' bytes');
                L('header (9 bytes): ' + hex(bytes.subarray(0, 9)));
                L('payload directives: ' + hex(bytes.subarray(9, 29)) + ' ...');

                const chunkSize = %d;

                // Send header first (9 bytes) with writeWithResponse
                const header = bytes.subarray(0, 9);
                try {
                  await wc.writeValueWithResponse(header);
                  L('header sent OK');
                } catch(e) {
                  E('failed to send header: ' + e.message);
                  return;
                }

                // Send payload in chunks
                const payload = bytes.subarray(9);
                const totalChunks = Math.ceil(payload.length / chunkSize);
                L('payload: ' + payload.length + ' bytes in ' + totalChunks + ' chunks');

                let chunkIndex = 0;
                const startTime = performance.now();
                for (let i = 0; i < payload.length; i += chunkSize) {
                  // Skip chunk index 27 (vendor app quirk)
                  if (chunkIndex === 27) chunkIndex++;

                  const chunkData = payload.subarray(i, Math.min(i + chunkSize, payload.length));
                  const isLastChunk = (i + chunkSize) >= payload.length;

                  let finalChunk;
                  if (isLastChunk) {
                    finalChunk = new Uint8Array([chunkIndex, ...chunkData, 0x12, 0x34]);
                  } else {
                    finalChunk = new Uint8Array([chunkIndex, ...chunkData]);
                  }

                  try {
                    await wc.writeValueWithResponse(finalChunk);
                    L('chunk ' + chunkIndex + ' (' + finalChunk.length + ' bytes' +
                      (isLastChunk ? ', last+magic' : '') + ')');
                  } catch(e) {
                    const elapsed = (performance.now() - startTime).toFixed(0);
                    E('write failed at chunk ' + chunkIndex + ' after ' + elapsed + 'ms: ' + e.message);
                    return;
                  }
                  chunkIndex++;
                }
                const elapsed = (performance.now() - startTime).toFixed(0);
                L('all ' + totalChunks + ' chunks sent in ' + elapsed + 'ms');
              },
              disconnect() {
                if (el._dymo.device && el._dymo.device.gatt.connected) {
                  L('disconnecting...');
                  el._dymo.device.gatt.disconnect();
                }
              }
            };
            // Auto-reconnect to previously paired device
            (async () => {
              try {
                if (!navigator.bluetooth.getDevices) return;
                const devices = await navigator.bluetooth.getDevices();
                for (const d of devices) {
                  if (d.gatt) {
                    L('found paired device: ' + d.name + ' - reconnecting...');
                    try {
                      await el._dymo._setupDevice(d);
                      return;
                    } catch(e) {
                      W('auto-reconnect failed for ' + d.name + ': ' + e.message);
                    }
                  }
                }
              } catch(e) {
                W('auto-reconnect not available: ' + e.message);
              }
            })();
            """.formatted(BLE_SERVICE_UUID, BLE_WRITE_CHAR_UUID, BLE_NOTIFY_CHAR_UUID,
                CHUNK_SIZE));
    }

    /**
     * Converts a BufferedImage to Dymo LetraTag 200B printer commands using the proper protocol.
     */
    byte[] buildPrintCommands(BufferedImage original) {
        LOG.info("buildPrintCommands: starting conversion, image size: " + original.getWidth() + "x" + original.getHeight());
        int width = original.getWidth();
        int height = original.getHeight();
        
        // LetraTag 200B has 32 pixels height (4 bytes per column = 32 bits)
        int printableHeight = Math.min(height, 32);
        
        // Convert to monochrome bitmap - LetraTag uses 1-bit per pixel, 4 bytes per vertical line (32 bits)
        // Each byte represents 8 pixels vertically, and we have 4 bytes per column (32 pixels total)
        int widthBytes = width; // Each column takes 4 bytes
        int totalBitmapSize = widthBytes * 4; // 4 bytes per column
        
        LOG.info("buildPrintCommands: width=" + width + " height=" + printableHeight
                + " widthBytes=" + widthBytes + " bitmapSize=" + totalBitmapSize);

        // Convert to monochrome bitmap
        // Each column is 4 bytes (32 bits), big-endian byte order, MSB first within byte
        byte[] bitmap = new byte[totalBitmapSize];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < printableHeight; y++) {
                int rgb = original.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r * 299 + g * 587 + b * 114) / 1000;

                if (gray < 128) {
                    int xOffset = x * 4;
                    int yOffset = 3 - (y / 8); // big-endian: byte 3 is top
                    int bitPosition = 7 - (y % 8);
                    bitmap[xOffset + yOffset] |= (byte) (1 << bitPosition);
                }
            }
        }

        // Build payload first so we know its length for the header
        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        // START directive
        payload.write(0x1B); payload.write(0x73);
        payload.write(0x9A); payload.write(0x02); payload.write(0x00); payload.write(0x00);

        // PRINT_DATA directive
        payload.write(0x1B); payload.write(0x44);
        payload.write(0x01); // bits per pixel
        payload.write(0x02); // alignment
        // Width = number of columns (4 bytes LE)
        payload.write(width & 0xFF);
        payload.write((width >> 8) & 0xFF);
        payload.write((width >> 16) & 0xFF);
        payload.write((width >> 24) & 0xFF);
        // Height = 32 (4 bytes LE)
        payload.write(0x20); payload.write(0x00); payload.write(0x00); payload.write(0x00);
        // Image data
        payload.writeBytes(bitmap);

        // FORM_FEED
        payload.write(0x1B); payload.write(0x45);
        // STATUS
        payload.write(0x1B); payload.write(0x41);
        // END
        payload.write(0x1B); payload.write(0x51);

        int payloadLength = payload.size();
        LOG.info("Payload: " + payloadLength + " bytes (bitmap: " + bitmap.length
                + " bytes for " + width + " columns)");

        // Build header (9 bytes): FF F0 12 34 [length LE 4] [checksum]
        byte[] header = new byte[9];
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xF0;
        header[2] = 0x12;
        header[3] = 0x34;
        header[4] = (byte) (payloadLength & 0xFF);
        header[5] = (byte) ((payloadLength >> 8) & 0xFF);
        header[6] = (byte) ((payloadLength >> 16) & 0xFF);
        header[7] = (byte) ((payloadLength >> 24) & 0xFF);
        // Checksum = sum of bytes 0..7 mod 256
        int checksum = 0;
        for (int i = 0; i < 8; i++) {
            checksum += header[i] & 0xFF;
        }
        header[8] = (byte) (checksum & 0xFF);

        LOG.info("Header: " + bytesToHex(header));

        // Combine header + payload
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(payload.toByteArray());
        return out.toByteArray();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
