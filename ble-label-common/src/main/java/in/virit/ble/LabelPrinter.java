package in.virit.ble;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Common interface for BLE label printers.
 */
public interface LabelPrinter {

    void requestConnection();

    void print(BufferedImage image);

    void disconnect();

    boolean isConnected();

    void addConnectionListener(Consumer<Boolean> listener);
}
