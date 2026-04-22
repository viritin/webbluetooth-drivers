package in.virit.dymo;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
    private static final int CHUNK_SIZE = LetraTagProtocol.CHUNK_SIZE;

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
     * Prints the given text as a single-line label. If the printer is not yet
     * connected, the browser's Bluetooth pairing dialog is opened automatically
     * and the text is printed once the connection is established.
     *
     * @param text the text to print
     */
    public void print(String text) {
        if (!connected) {
            addConnectionListener(new Consumer<Boolean>() {
                @Override
                public void accept(Boolean nowConnected) {
                    connectionListeners.remove(this);
                    if (nowConnected) {
                        print(text);
                    }
                }
            });
            requestConnection();
            return;
        }
        print(renderText(text));
    }

    private static BufferedImage renderText(String text) {
        int height = LetraTagProtocol.PRINTABLE_HEIGHT_PX;
        int fontSize = 28;
        int marginX = 8;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int baseline = (height + fm.getAscent() - fm.getDescent()) / 2;
        pg.dispose();

        int width = textWidth + 2 * marginX;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, marginX, baseline);
        g.dispose();
        return img;
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
        BufferedImage stretched = LetraTagProtocol.stretchHorizontally(image);
        LOG.info("Stretched to: " + stretched.getWidth() + "x" + stretched.getHeight());
        byte[] commandBytes = LetraTagProtocol.buildPrintCommands(stretched);
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
        new ArrayList<>(connectionListeners).forEach(l -> l.accept(connected));
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
}
