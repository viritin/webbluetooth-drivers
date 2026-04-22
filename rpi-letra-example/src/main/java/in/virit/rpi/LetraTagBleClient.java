package in.virit.rpi;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService;
import org.freedesktop.dbus.DBusPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to a Dymo LetraTag 200B over BlueZ. Finds the device by name prefix
 * ("Letratag"), connects to the vendor GATT service, and writes the framing
 * header followed by 500-byte payload chunks exactly like the vendor mobile
 * app and the Web Bluetooth driver do.
 */
class LetraTagBleClient {

    private static final Logger log = LoggerFactory.getLogger(LetraTagBleClient.class);

    private static final String SERVICE_UUID = "be3dd650-2b3d-42f1-99c1-f0f749dd0678";
    private static final String WRITE_CHAR_UUID = "be3dd651-2b3d-42f1-99c1-f0f749dd0678";
    private static final String NAME_PREFIX = "Letratag";
    private static final int CHUNK_SIZE = in.virit.dymo.LetraTagProtocol.CHUNK_SIZE;
    private static final int QUIRK_SKIP_INDEX = 27;

    private final DeviceManager deviceManager;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic writeChar;

    LetraTagBleClient() throws Exception {
        try {
            this.deviceManager = DeviceManager.createInstance(false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to open D-Bus connection. This app needs BlueZ over D-Bus "
                            + "— run it on Linux (e.g. Raspberry Pi OS) with the `bluez` "
                            + "package installed, not on macOS/Windows.", e);
        }
    }

    /**
     * Scans for a LetraTag device and opens a GATT connection. Safe to call
     * again to reconnect.
     */
    synchronized void connect() throws Exception {
        if (isConnected()) {
            return;
        }

        // Clear any stale reference from a previous session before re-scanning.
        // If we leave BlueZ's cached device entry untouched, the next connect
        // often aborts with "le-connection-abort-by-local" because the kernel
        // link manager still holds the old half-dead LE session.
        dropStaleDevice();

        log.info("Scanning for LetraTag device (8s)...");
        deviceManager.scanForBluetoothDevices(8000);

        List<BluetoothDevice> devices = deviceManager.getDevices();
        device = devices.stream()
                .filter(d -> d.getName() != null && d.getName().startsWith(NAME_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No device with name prefix '" + NAME_PREFIX + "' found. "
                                + "Make sure the printer is powered on and discoverable."));
        log.info("Found {} ({})", device.getName(), device.getAddress());

        // BlueZ refuses to open LE connections while discovery is active
        // ("le-connection-abort-by-local"). scanForBluetoothDevices usually
        // stops it, but on busy adapters it can linger — force it off.
        try {
            deviceManager.getAdapter().stopDiscovery();
        } catch (Exception ignore) {
            // already stopped
        }
        Thread.sleep(500);

        // If BlueZ still thinks the device is connected from a dropped session,
        // tear it down first so this connect can start clean.
        if (Boolean.TRUE.equals(device.isConnected())) {
            log.info("Disconnecting stale GATT session before reconnect");
            try {
                device.disconnect();
            } catch (Exception ignore) {
            }
            Thread.sleep(1000);
        }

        if (!device.connect()) {
            throw new IllegalStateException("GATT connect failed for " + device.getAddress());
        }
        log.info("Connected GATT");

        BluetoothGattService service = awaitService();
        writeChar = service.getGattCharacteristicByUuid(WRITE_CHAR_UUID);
        if (writeChar == null) {
            throw new IllegalStateException("Write characteristic not found on device");
        }
        log.info("Ready to print");
    }

    /**
     * Drops cached state from a previous attempt: a graceful disconnect
     * followed by asking BlueZ to remove its device record. This forces the
     * next scan to rediscover the printer and gives the kernel link manager
     * a fresh LE session — without it, BlueZ keeps handing us a stale entry
     * and the next connect aborts with le-connection-abort-by-local.
     */
    private void dropStaleDevice() {
        if (device == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(device.isConnected())) {
                device.disconnect();
            }
        } catch (Exception ignore) {
        }
        try {
            String path = device.getDbusPath();
            if (path != null) {
                deviceManager.getAdapter().getRawAdapter().RemoveDevice(new DBusPath(path));
            }
        } catch (Throwable t) {
            log.debug("RemoveDevice failed (ok if entry is gone): {}", t.toString());
        }
        device = null;
        writeChar = null;
    }

    /** Polls briefly for service discovery to populate after connect(). */
    private BluetoothGattService awaitService() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            BluetoothGattService service = device.getGattServiceByUuid(SERVICE_UUID);
            if (service != null) {
                return service;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("GATT service " + SERVICE_UUID + " not discovered");
    }

    synchronized boolean isConnected() {
        return device != null && Boolean.TRUE.equals(device.isConnected()) && writeChar != null;
    }

    synchronized void disconnect() {
        if (device != null && Boolean.TRUE.equals(device.isConnected())) {
            try {
                device.disconnect();
            } catch (Exception e) {
                log.warn("Disconnect failed: {}", e.toString());
            }
        }
        writeChar = null;
    }

    /**
     * Writes the 9-byte header and then the payload in CHUNK_SIZE-byte pieces.
     * Each payload chunk is prefixed with a monotonically increasing index
     * byte (index 27 is skipped — a vendor-app quirk). The final chunk is
     * suffixed with the magic end-of-job bytes {@code 0x12 0x34}.
     */
    synchronized void sendPrintJob(byte[] framed) throws Exception {
        if (writeChar == null) {
            throw new IllegalStateException("Not connected");
        }
        if (framed.length < 9) {
            throw new IllegalArgumentException("Framed payload too short");
        }
        Map<String, Object> options = new HashMap<>();
        options.put("type", "request");

        byte[] header = new byte[9];
        System.arraycopy(framed, 0, header, 0, 9);
        writeChar.writeValue(header, options);

        int payloadLen = framed.length - 9;
        int chunkIndex = 0;
        long start = System.currentTimeMillis();
        for (int offset = 0; offset < payloadLen; offset += CHUNK_SIZE) {
            if (chunkIndex == QUIRK_SKIP_INDEX) {
                chunkIndex++;
            }
            int end = Math.min(offset + CHUNK_SIZE, payloadLen);
            boolean isLast = end >= payloadLen;
            int chunkLen = end - offset;
            byte[] out = new byte[1 + chunkLen + (isLast ? 2 : 0)];
            out[0] = (byte) chunkIndex;
            System.arraycopy(framed, 9 + offset, out, 1, chunkLen);
            if (isLast) {
                out[1 + chunkLen] = 0x12;
                out[2 + chunkLen] = 0x34;
            }
            writeChar.writeValue(out, options);
            chunkIndex++;
        }
        log.info("Sent {} bytes in {} chunk(s) in {} ms",
                framed.length, chunkIndex, System.currentTimeMillis() - start);
    }
}
