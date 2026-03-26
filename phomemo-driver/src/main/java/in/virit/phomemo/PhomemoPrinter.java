package in.virit.phomemo;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A Vaadin component that connects to a Phomemo label printer
 * via the Web Bluetooth API and prints images.
 * <p>
 * The component is non-visual — add it to any layout and call
 * {@link #requestConnection()} to trigger the browser's Bluetooth
 * pairing dialog, then {@link #print(BufferedImage)} to send an image.
 */
@Tag("phomemo-printer")
public class PhomemoPrinter extends Component {

    private static final Logger LOG = Logger.getLogger(PhomemoPrinter.class.getName());
    private static final String BLE_SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb";
    private static final String BLE_WRITE_CHAR_UUID = "0000ff02-0000-1000-8000-00805f9b34fb";
    private static final int CHUNK_SIZE = 128;

    private boolean connected;
    private final List<Consumer<Boolean>> connectionListeners = new ArrayList<>();

    public PhomemoPrinter() {
        getElement().getStyle().set("display", "none");
        initJs();
    }

    /**
     * Opens the browser's Bluetooth pairing dialog to connect to a Phomemo printer.
     */
    public void requestConnection() {
        getElement().executeJs("return this._phomemo.connect()");
    }

    /**
     * Prints the given image on the connected Phomemo printer.
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
        getElement().executeJs("return this._phomemo.sendData($0)", base64);
    }

    /**
     * Returns whether the printer is currently connected.
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Adds a listener that is notified when the connection state changes.
     *
     * @param listener receives {@code true} on connect, {@code false} on disconnect
     */
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
        getElement().executeJs("this._phomemo.disconnect()");
    }

    private void initJs() {
        getElement().executeJs("""
            const el = this;
            const SERVICE = '%s';
            const WRITE_CHAR = '%s';
            el._phomemo = {
              device: null,
              server: null,
              writeChar: null,
              notifyChar: null,
              async _setupDevice(device) {
                el._phomemo.device = device;
                console.log('Phomemo: device:', device.name, 'id:', device.id);

                device.addEventListener('gattserverdisconnected', () => {
                  console.warn('Phomemo: device disconnected!');
                  el.$server.onConnectionChange(false);
                });

                console.log('Phomemo: connecting GATT server...');
                const server = await device.gatt.connect();
                el._phomemo.server = server;
                console.log('Phomemo: GATT connected:', server.connected);

                const service = await server.getPrimaryService(SERVICE);
                console.log('Phomemo: got service:', service.uuid);

                const chars = await service.getCharacteristics();
                el._phomemo.writeChar = await service.getCharacteristic(WRITE_CHAR);

                for (const c of chars) {
                  if (c.properties.notify || c.properties.indicate) {
                    try {
                      await c.startNotifications();
                      c.addEventListener('characteristicvaluechanged', (event) => {
                        const value = new Uint8Array(event.target.value.buffer);
                        const hex = Array.from(value).map(b => b.toString(16).padStart(2, '0')).join(' ');
                        console.log('Phomemo: notification from', c.uuid, ':', hex, '(' + value.length + ' bytes)');
                      });
                      el._phomemo.notifyChar = c;
                      console.log('Phomemo: subscribed to notifications on', c.uuid);
                    } catch(e) {
                      console.warn('Phomemo: failed to subscribe to', c.uuid, ':', e.message);
                    }
                  }
                }

                console.log('Phomemo: connection complete. Ready to print.');
                el.$server.onConnectionChange(true);
              },
              async connect() {
                console.log('Phomemo: requesting device...');
                const device = await navigator.bluetooth.requestDevice({
                  filters: [
                    {namePrefix : 'Q199E48H0030481'}
                  ],
                  optionalServices: [SERVICE]
                });
                await el._phomemo._setupDevice(device);
              },
              async sendData(base64) {
                const wc = el._phomemo.writeChar;
                if (!wc) { console.error('Phomemo: not connected'); return; }
                const raw = atob(base64);
                const bytes = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);

                // Log the first 32 bytes of the command for debugging
                const headerHex = Array.from(bytes.slice(0, 32)).map(b => b.toString(16).padStart(2, '0')).join(' ');
                console.log('Phomemo: command header:', headerHex);
                // Log the last 16 bytes (footer)
                const footerHex = Array.from(bytes.slice(-16)).map(b => b.toString(16).padStart(2, '0')).join(' ');
                console.log('Phomemo: command footer:', footerHex);

                const chunkSize = %d;
                const totalChunks = Math.ceil(bytes.length / chunkSize);
                console.log('Phomemo: sending ' + bytes.length + ' bytes in ' + totalChunks + ' chunks (chunk size: ' + chunkSize + ')');

                // Check GATT connection state before sending
                if (el._phomemo.device && !el._phomemo.device.gatt.connected) {
                  console.error('Phomemo: GATT not connected! Aborting send.');
                  return;
                }

                let sent = 0;
                const startTime = performance.now();
                for (let i = 0; i < bytes.length; i += chunkSize) {
                  const slice = bytes.slice(i, Math.min(i + chunkSize, bytes.length));
                  try {
                    await wc.writeValueWithResponse(slice);
                    sent++;
                    if (sent %% 50 === 0) {
                      console.log('Phomemo: progress ' + sent + '/' + totalChunks + ' chunks');
                    }
                  } catch(e) {
                    const elapsed = (performance.now() - startTime).toFixed(0);
                    console.error('Phomemo: write failed at chunk ' + sent + '/' + totalChunks + ' after ' + elapsed + 'ms: ' + e.message);
                    console.error('Phomemo: failed chunk size was', slice.length, 'bytes');
                    return;
                  }
                }
                const elapsed = (performance.now() - startTime).toFixed(0);
                console.log('Phomemo: sent ' + sent + ' chunks OK in ' + elapsed + 'ms');
              },
              disconnect() {
                if (el._phomemo.device && el._phomemo.device.gatt.connected) {
                  console.log('Phomemo: disconnecting...');
                  el._phomemo.device.gatt.disconnect();
                }
              }
            };
            // Auto-reconnect to a previously paired device
            (async () => {
              try {
                if (!navigator.bluetooth.getDevices) return;
                const devices = await navigator.bluetooth.getDevices();
                for (const d of devices) {
                  if (d.gatt) {
                    console.log('Phomemo: found paired device:', d.name, '- attempting reconnect...');
                    try {
                      await el._phomemo._setupDevice(d);
                      return;
                    } catch(e) {
                      console.warn('Phomemo: auto-reconnect failed for', d.name, ':', e.message);
                    }
                  }
                }
              } catch(e) {
                console.warn('Phomemo: auto-reconnect not available:', e.message);
              }
            })();
            """.formatted(BLE_SERVICE_UUID, BLE_WRITE_CHAR_UUID, CHUNK_SIZE));
    }

    /**
     * Converts a BufferedImage to Phomemo printer commands.
     */
    byte[] buildPrintCommands(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();
        int widthBytes = (width + 7) / 8;
        LOG.info("buildPrintCommands: width=" + width + " height=" + height
                + " widthBytes=" + widthBytes + " bitmapSize=" + (widthBytes * height));

        // Convert to monochrome bitmap (1 = black, 0 = white)
        byte[] bitmap = new byte[widthBytes * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = original.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r * 299 + g * 587 + b * 114) / 1000;
                if (gray < 128) {
                    bitmap[y * widthBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // M110-style header: speed, density, media type
        out.write(0x1b); out.write(0x4e); out.write(0x0d); out.write(0x05);
        out.write(0x1b); out.write(0x4e); out.write(0x04); out.write(0x0a);
        out.write(0x1f); out.write(0x11); out.write(0x0a);

        // GS v 0 — raster image block
        out.write(0x1d); out.write(0x76); out.write(0x30); out.write(0x00);
        out.write(widthBytes & 0xFF);
        out.write((widthBytes >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((height >> 8) & 0xFF);

        // Image data
        out.writeBytes(bitmap);

        // Footer — end print job
        out.write(0x1f); out.write(0xf0); out.write(0x05); out.write(0x00);
        out.write(0x1f); out.write(0xf0); out.write(0x03); out.write(0x00);

        return out.toByteArray();
    }
}
