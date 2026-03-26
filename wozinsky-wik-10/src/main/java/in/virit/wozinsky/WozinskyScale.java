package in.virit.wozinsky;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.shared.Registration;

/**
 * A Vaadin component that connects to a Chipsea CST34XX-based BLE kitchen scale
 * (e.g. Wozinsky WIK-10) via the Web Bluetooth API and reports weight measurements.
 * <p>
 * Includes a weight display and a connect/disconnect toggle button.
 * <p>
 * Uses the custom Chipsea GATT service 0xFFB0 with characteristics:
 * <ul>
 *   <li>0xFFB1 — write (commands)</li>
 *   <li>0xFFB2 — notify (weight data)</li>
 * </ul>
 * <p>
 * Protocol (8 bytes): {@code AC 05 XX WH WL XX SS CK}
 * <ul>
 *   <li>Bytes 0-1: header (0xAC 0x05)</li>
 *   <li>Byte 2: reserved</li>
 *   <li>Bytes 3-4: weight in grams (big-endian uint16)</li>
 *   <li>Byte 5: reserved</li>
 *   <li>Byte 6: status (0xCA = stable, 0xCE = measuring)</li>
 *   <li>Byte 7: checksum</li>
 * </ul>
 */
public class WozinskyScale extends VerticalLayout {

    private final BleConnector connector = new BleConnector();
    private final H1 weightDisplay = new H1("-- g");
    private final Button toggleBtn = new Button(VaadinIcon.CONNECT.create());
    private boolean connected;

    public WozinskyScale() {
        getStyle().setPosition(Style.Position.RELATIVE);

        weightDisplay.getStyle()
                .setFontSize("4em");
        weightDisplay.getStyle().set("font-family", "monospace");

        toggleBtn.getStyle()
                .setPosition(Style.Position.ABSOLUTE)
                .setTop("0")
                .setRight("0");

        updateDisconnectedState();

        toggleBtn.addClickListener(e -> {
            if (connected) {
                disconnect();
            } else {
                requestConnection();
            }
        });

        add(weightDisplay, toggleBtn, connector);

        connector.addWeightListener(e -> {
            weightDisplay.setText("%d g%s".formatted(e.getWeightGrams(), e.isStable() ? "" : " ~"));
            weightDisplay.getStyle().setColor(e.isStable() ? "black" : "gray");
            fireEvent(new WeightEvent(this, false, e.getWeightGrams(), e.isStable()));
        });

        connector.addConnectionListener(e -> {
            connected = e.isConnected();
            if (connected) {
                updateConnectedState();
            } else {
                updateDisconnectedState();
            }
        });

        connector.addLogListener(e ->
                fireEvent(new LogEvent(this, false, e.getMessage())));
    }

    private void updateConnectedState() {
        toggleBtn.setIcon(VaadinIcon.UNLINK.create());
        weightDisplay.getStyle().setColor("black");
    }

    private void updateDisconnectedState() {
        toggleBtn.setIcon(VaadinIcon.CONNECT.create());
        weightDisplay.setText("-- g");
        weightDisplay.getStyle().setColor("lightgray");
    }

    /**
     * Opens the browser's Bluetooth pairing dialog to connect to the scale.
     */
    public void requestConnection() {
        connector.requestConnection();
    }

    /**
     * Disconnects from the scale.
     */
    public void disconnect() {
        connector.disconnect();
    }

    /**
     * Adds a listener for weight measurement events.
     *
     * @param listener the listener
     * @return a registration to remove the listener
     */
    public Registration addWeightListener(ComponentEventListener<WeightEvent> listener) {
        return addListener(WeightEvent.class, listener);
    }

    /**
     * Adds a listener for log/debug events from the BLE connection.
     *
     * @param listener the listener
     * @return a registration to remove the listener
     */
    public Registration addLogListener(ComponentEventListener<LogEvent> listener) {
        return addListener(LogEvent.class, listener);
    }

    @Tag("wozinsky-scale-ble")
    private static class BleConnector extends Component {

        BleConnector() {
            getElement().getStyle().set("display", "none");
            initJs();
        }

        void requestConnection() {
            getElement().executeJs("return this._scale.connect()");
        }

        void disconnect() {
            getElement().executeJs("this._scale.disconnect()");
        }

        Registration addWeightListener(ComponentEventListener<InternalWeightEvent> listener) {
            return addListener(InternalWeightEvent.class, listener);
        }

        Registration addConnectionListener(ComponentEventListener<ConnectionEvent> listener) {
            return addListener(ConnectionEvent.class, listener);
        }

        Registration addLogListener(ComponentEventListener<LogEvent> listener) {
            return addListener(LogEvent.class, listener);
        }

        private void initJs() {
            getElement().executeJs("""
                const el = this;
                const SERVICE_UUID = 0xFFB0;
                const NOTIFY_CHAR_UUID = 0xFFB2;

                el._scale = {
                  device: null,
                  async setupDevice(device) {
                    el._scale.device = device;

                    device.addEventListener('gattserverdisconnected', () => {
                      el.$server.onConnectionChanged(0);
                    });

                    const server = await device.gatt.connect();
                    const service = await server.getPrimaryService(SERVICE_UUID);
                    const notifyChar = await service.getCharacteristic(NOTIFY_CHAR_UUID);

                    let lastWeight = -1, lastStable = -1;
                    notifyChar.addEventListener('characteristicvaluechanged', (event) => {
                      const v = event.target.value;
                      if (v.byteLength < 6) return;
                      const bytes = new Uint8Array(v.buffer);
                      const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(' ');
                      if (v.getUint8(0) !== 0xAC || v.getUint8(1) !== 0x05) {
                        console.log('Scale: unknown packet:', hex);
                        return;
                      }
                      console.log('Scale: raw:', hex, '(' + v.byteLength + ' bytes)');

                      const weightGrams = v.getUint16(3, false);
                      const status = v.getUint8(6);
                      const stable = (status === 0xCA) ? 1 : 0;
                      console.log('Scale: weight=' + weightGrams + 'g stable=' + stable);
                      if (weightGrams === lastWeight && stable === lastStable) return;
                      lastWeight = weightGrams;
                      lastStable = stable;
                      el.$server.onWeight(weightGrams, stable);
                    });
                    await notifyChar.startNotifications();
                    el.$server.onConnectionChanged(1);
                  },
                  async connect() {
                    const device = await navigator.bluetooth.requestDevice({
                      filters: [{name: 'SCALE'}],
                      optionalServices: [SERVICE_UUID]
                    });
                    await el._scale.setupDevice(device);
                  },
                  disconnect() {
                    if (el._scale.device && el._scale.device.gatt && el._scale.device.gatt.connected) {
                      el._scale.device.gatt.disconnect();
                    }
                  }
                };

                // Auto-connect to a previously paired device
                (async () => {
                  if (!navigator.bluetooth || !navigator.bluetooth.getDevices) {
                    el.$server.onLog('Auto-connect: getDevices() not available');
                    return;
                  }
                  try {
                    const devices = await navigator.bluetooth.getDevices();
                    el.$server.onLog('Auto-connect: found ' + devices.length + ' paired device(s)' +
                      (devices.length === 0 ? ' (enable chrome://flags/#enable-web-bluetooth-new-permissions-backend)' : ''));
                    for (const device of devices) {
                      el.$server.onLog('Auto-connect: device "' + device.name + '" id=' + device.id);
                      if (device.name === 'SCALE') {
                        el.$server.onLog('Auto-connect: watching for SCALE advertisements...');
                        const ac = new AbortController();
                        device.addEventListener('advertisementreceived', async () => {
                          el.$server.onLog('Auto-connect: advertisement received, connecting...');
                          ac.abort();
                          try {
                            await el._scale.setupDevice(device);
                          } catch(e) { el.$server.onLog('Auto-connect failed: ' + e.message); }
                        }, {once: true});
                        try {
                          await device.watchAdvertisements({signal: ac.signal});
                          el.$server.onLog('Auto-connect: watchAdvertisements() started');
                        } catch(e) {
                          el.$server.onLog('Auto-connect: watchAdvertisements() failed: ' + e.message);
                        }
                        break;
                      }
                    }
                  } catch(e) { el.$server.onLog('Auto-connect error: ' + e.message); }
                })();
                """);
        }

        @com.vaadin.flow.component.ClientCallable
        void onWeight(int weightGrams, int stable) {
            fireEvent(new InternalWeightEvent(this, false, weightGrams, stable == 1));
        }

        @com.vaadin.flow.component.ClientCallable
        void onConnectionChanged(int connected) {
            fireEvent(new ConnectionEvent(this, false, connected == 1));
        }

        @com.vaadin.flow.component.ClientCallable
        void onLog(String message) {
            fireEvent(new LogEvent(this, false, message));
        }

        static class InternalWeightEvent extends ComponentEvent<BleConnector> {
            private final int weightGrams;
            private final boolean stable;

            InternalWeightEvent(BleConnector source, boolean fromClient, int weightGrams, boolean stable) {
                super(source, fromClient);
                this.weightGrams = weightGrams;
                this.stable = stable;
            }

            int getWeightGrams() { return weightGrams; }
            boolean isStable() { return stable; }
        }

        static class ConnectionEvent extends ComponentEvent<BleConnector> {
            private final boolean connected;

            ConnectionEvent(BleConnector source, boolean fromClient, boolean connected) {
                super(source, fromClient);
                this.connected = connected;
            }

            boolean isConnected() { return connected; }
        }

        static class LogEvent extends ComponentEvent<BleConnector> {
            private final String message;

            LogEvent(BleConnector source, boolean fromClient, String message) {
                super(source, fromClient);
                this.message = message;
            }

            String getMessage() { return message; }
        }
    }

    /**
     * Event fired when a weight measurement is received from the scale.
     */
    public static class WeightEvent extends ComponentEvent<WozinskyScale> {

        private final int weightGrams;
        private final boolean stable;

        public WeightEvent(WozinskyScale source, boolean fromClient, int weightGrams, boolean stable) {
            super(source, fromClient);
            this.weightGrams = weightGrams;
            this.stable = stable;
        }

        /**
         * Returns the weight in grams.
         */
        public int getWeightGrams() {
            return weightGrams;
        }

        /**
         * Returns the weight in kilograms.
         */
        public double getWeightKg() {
            return weightGrams / 1000.0;
        }

        /**
         * Returns whether the measurement is stable (settled).
         */
        public boolean isStable() {
            return stable;
        }
    }

    /**
     * Event fired for log/debug messages.
     */
    public static class LogEvent extends ComponentEvent<WozinskyScale> {

        private final String message;

        public LogEvent(WozinskyScale source, boolean fromClient, String message) {
            super(source, fromClient);
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }
}
