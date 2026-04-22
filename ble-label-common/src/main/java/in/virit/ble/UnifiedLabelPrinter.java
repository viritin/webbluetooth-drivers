package in.virit.ble;

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
 * A unified BLE label printer component that supports multiple printer types
 * (Phomemo M110, Dymo LetraTag 200B) through a single connection dialog.
 * <p>
 * The browser's Bluetooth pairing dialog shows all supported devices.
 * After connection, the printer type is auto-detected based on available
 * BLE services, and the appropriate protocol is used for printing.
 * <p>
 * Usage:
 * <pre>
 * var printer = new UnifiedLabelPrinter();
 * printer.addPrinterTypeListener(type -> {
 *     // adjust label format based on printer type
 * });
 * printer.requestConnection();
 * printer.print(image);
 * </pre>
 */
@Tag("unified-label-printer")
public class UnifiedLabelPrinter extends Component implements LabelPrinter {

    private static final Logger LOG = Logger.getLogger(UnifiedLabelPrinter.class.getName());

    private static final String PHOMEMO_SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb";
    private static final String PHOMEMO_WRITE_CHAR = "0000ff02-0000-1000-8000-00805f9b34fb";
    private static final String DYMO_SERVICE = "be3dd650-2b3d-42f1-99c1-f0f749dd0678";
    private static final String DYMO_WRITE_CHAR = "be3dd651-2b3d-42f1-99c1-f0f749dd0678";
    private static final String DYMO_NOTIFY_CHAR = "be3dd652-2b3d-42f1-99c1-f0f749dd0678";

    private boolean connected;
    private PrinterType printerType;
    private final List<Consumer<Boolean>> connectionListeners = new ArrayList<>();
    private final List<Consumer<PrinterType>> printerTypeListeners = new ArrayList<>();

    public enum PrinterType {
        PHOMEMO,
        DYMO_LETRATAG
    }

    public UnifiedLabelPrinter() {
        getElement().getStyle().set("display", "none");
        initJs();
    }

    @Override
    public void requestConnection() {
        getElement().executeJs("return this._printer.connect()");
    }

    @Override
    public void print(BufferedImage image) {
        if (printerType == null) {
            LOG.warning("No printer connected");
            return;
        }
        byte[] commands = switch (printerType) {
            case PHOMEMO -> buildPhomemoCommands(image);
            case DYMO_LETRATAG -> buildDymoCommands(stretchHorizontally(image));
        };
        LOG.info("Print: " + commands.length + " bytes for " + printerType);
        String base64 = Base64.getEncoder().encodeToString(commands);
        getElement().executeJs("return this._printer.sendData($0)", base64);
    }

    @Override
    public void disconnect() {
        getElement().executeJs("this._printer.disconnect()");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * Returns the detected printer type, or null if not connected.
     */
    public PrinterType getPrinterType() {
        return printerType;
    }

    @Override
    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }

    /**
     * Adds a listener that is called when the printer type is detected after connection.
     */
    public void addPrinterTypeListener(Consumer<PrinterType> listener) {
        printerTypeListeners.add(listener);
    }

    @ClientCallable
    private void onConnectionChange(boolean connected, String type) {
        this.connected = connected;
        if (connected && type != null) {
            this.printerType = "phomemo".equals(type) ? PrinterType.PHOMEMO : PrinterType.DYMO_LETRATAG;
            printerTypeListeners.forEach(l -> l.accept(printerType));
        } else if (!connected) {
            this.printerType = null;
        }
        connectionListeners.forEach(l -> l.accept(connected));
    }

    private void initJs() {
        getElement().executeJs("""
            const el = this;
            const PHOMEMO_SERVICE = '%s';
            const PHOMEMO_WRITE = '%s';
            const DYMO_SERVICE = '%s';
            const DYMO_WRITE = '%s';
            const DYMO_NOTIFY = '%s';
            const L = (msg) => console.log('Printer: ' + msg);

            el._printer = {
              device: null, writeChar: null, type: null,

              async _setup(device) {
                el._printer.device = device;
                L('device: ' + device.name);
                device.addEventListener('gattserverdisconnected', () => {
                  L('disconnected');
                  el.$server.onConnectionChange(false, null);
                });

                const server = await device.gatt.connect();
                L('GATT connected');

                // Detect printer type by trying services
                let service, type;
                try {
                  service = await server.getPrimaryService(PHOMEMO_SERVICE);
                  type = 'phomemo';
                  L('detected Phomemo');
                } catch(e) {
                  try {
                    service = await server.getPrimaryService(DYMO_SERVICE);
                    type = 'dymo';
                    L('detected Dymo LetraTag');
                  } catch(e2) {
                    L('no known service found');
                    return;
                  }
                }
                el._printer.type = type;

                el._printer.writeChar = await service.getCharacteristic(
                  type === 'phomemo' ? PHOMEMO_WRITE : DYMO_WRITE);

                // Subscribe to notifications
                const chars = await service.getCharacteristics();
                for (const c of chars) {
                  if (c.properties.notify || c.properties.indicate) {
                    try {
                      await c.startNotifications();
                      c.addEventListener('characteristicvaluechanged', (event) => {
                        const value = new Uint8Array(event.target.value.buffer);
                        const hex = Array.from(value).map(b => b.toString(16).padStart(2, '0')).join(' ');
                        L('notification: ' + hex);
                      });
                    } catch(e) {}
                  }
                }

                L('ready (' + type + ')');
                el.$server.onConnectionChange(true, type);
              },

              async connect() {
                L('requesting device...');
                const device = await navigator.bluetooth.requestDevice({
                  filters: [
                    {namePrefix: 'Q199E48H0030481'},
                    {namePrefix: 'Letratag'}
                  ],
                  optionalServices: [PHOMEMO_SERVICE, DYMO_SERVICE]
                });
                await el._printer._setup(device);
              },

              async sendData(base64) {
                const wc = el._printer.writeChar;
                if (!wc) return;
                const raw = atob(base64);
                const bytes = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);

                if (el._printer.type === 'phomemo') {
                  await el._printer._sendPhomemo(wc, bytes);
                } else {
                  await el._printer._sendDymo(wc, bytes);
                }
              },

              async _sendPhomemo(wc, bytes) {
                const chunkSize = 128;
                const total = Math.ceil(bytes.length / chunkSize);
                L('sending ' + bytes.length + ' bytes in ' + total + ' chunks');
                for (let i = 0; i < bytes.length; i += chunkSize) {
                  const slice = bytes.slice(i, Math.min(i + chunkSize, bytes.length));
                  await wc.writeValueWithResponse(slice);
                }
                L('done');
              },

              async _sendDymo(wc, bytes) {
                const chunkSize = 500;
                const header = bytes.subarray(0, 9);
                await wc.writeValueWithResponse(header);
                L('header sent');

                const payload = bytes.subarray(9);
                let idx = 0;
                for (let i = 0; i < payload.length; i += chunkSize) {
                  if (idx === 27) idx++;
                  const chunk = payload.subarray(i, Math.min(i + chunkSize, payload.length));
                  const isLast = (i + chunkSize) >= payload.length;
                  let final_chunk;
                  if (isLast) {
                    final_chunk = new Uint8Array([idx, ...chunk, 0x12, 0x34]);
                  } else {
                    final_chunk = new Uint8Array([idx, ...chunk]);
                  }
                  await wc.writeValueWithResponse(final_chunk);
                  idx++;
                }
                L('done');
              },

              disconnect() {
                if (el._printer.device && el._printer.device.gatt.connected) {
                  el._printer.device.gatt.disconnect();
                }
              }
            };

            // Auto-reconnect
            (async () => {
              try {
                if (!navigator.bluetooth.getDevices) return;
                const devices = await navigator.bluetooth.getDevices();
                for (const d of devices) {
                  if (d.gatt) {
                    L('found paired device: ' + d.name + ' - reconnecting...');
                    try {
                      await el._printer._setup(d);
                      return;
                    } catch(e) {
                      L('auto-reconnect failed: ' + e.message);
                    }
                  }
                }
              } catch(e) {}
            })();
            """.formatted(PHOMEMO_SERVICE, PHOMEMO_WRITE_CHAR,
                DYMO_SERVICE, DYMO_WRITE_CHAR, DYMO_NOTIFY_CHAR));
    }

    // ---- Phomemo protocol ----

    private byte[] buildPhomemoCommands(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();
        int widthBytes = (width + 7) / 8;

        byte[] bitmap = new byte[widthBytes * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (isBlackPixel(original, x, y)) {
                    bitmap[y * widthBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Header
        out.write(0x1b); out.write(0x4e); out.write(0x0d); out.write(0x05);
        out.write(0x1b); out.write(0x4e); out.write(0x04); out.write(0x0a);
        out.write(0x1f); out.write(0x11); out.write(0x0a);
        // GS v 0
        out.write(0x1d); out.write(0x76); out.write(0x30); out.write(0x00);
        out.write(widthBytes & 0xFF); out.write((widthBytes >> 8) & 0xFF);
        out.write(height & 0xFF); out.write((height >> 8) & 0xFF);
        out.writeBytes(bitmap);
        // Footer
        out.write(0x1f); out.write(0xf0); out.write(0x05); out.write(0x00);
        out.write(0x1f); out.write(0xf0); out.write(0x03); out.write(0x00);
        return out.toByteArray();
    }

    // ---- Dymo LetraTag 200B protocol ----

    private byte[] buildDymoCommands(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();
        int printableHeight = Math.min(height, 32);

        byte[] bitmap = new byte[width * 4];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < printableHeight; y++) {
                if (isBlackPixel(original, x, y)) {
                    int xOffset = x * 4;
                    int yOffset = 3 - (y / 8);
                    int bitPosition = 7 - (y % 8);
                    bitmap[xOffset + yOffset] |= (byte) (1 << bitPosition);
                }
            }
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        // START
        payload.write(0x1B); payload.write(0x73);
        payload.write(0x9A); payload.write(0x02); payload.write(0x00); payload.write(0x00);
        // PRINT_DATA
        payload.write(0x1B); payload.write(0x44);
        payload.write(0x01); payload.write(0x02);
        payload.write(width & 0xFF); payload.write((width >> 8) & 0xFF);
        payload.write((width >> 16) & 0xFF); payload.write((width >> 24) & 0xFF);
        payload.write(0x20); payload.write(0x00); payload.write(0x00); payload.write(0x00);
        payload.writeBytes(bitmap);
        // FORM_FEED, STATUS, END
        payload.write(0x1B); payload.write(0x45);
        payload.write(0x1B); payload.write(0x41);
        payload.write(0x1B); payload.write(0x51);

        int payloadLength = payload.size();
        byte[] header = new byte[9];
        header[0] = (byte) 0xFF; header[1] = (byte) 0xF0;
        header[2] = 0x12; header[3] = 0x34;
        header[4] = (byte) (payloadLength & 0xFF);
        header[5] = (byte) ((payloadLength >> 8) & 0xFF);
        header[6] = (byte) ((payloadLength >> 16) & 0xFF);
        header[7] = (byte) ((payloadLength >> 24) & 0xFF);
        int checksum = 0;
        for (int i = 0; i < 8; i++) checksum += header[i] & 0xFF;
        header[8] = (byte) (checksum & 0xFF);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(payload.toByteArray());
        return out.toByteArray();
    }

    private static BufferedImage stretchHorizontally(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage stretched = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                stretched.setRGB(x * 2, y, rgb);
                stretched.setRGB(x * 2 + 1, y, rgb);
            }
        }
        return stretched;
    }

    private static boolean isBlackPixel(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int gray = (r * 299 + g * 587 + b * 114) / 1000;
        return gray < 128;
    }
}
