package in.virit.rpi;

import in.virit.dymo.LetraTagProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.net.URI;

/**
 * Entry point for the Raspberry Pi bridge. Finds a Dymo LetraTag 200B over
 * BLE and registers with the print server at wss://w.virit.in/ws/printer.
 * Each text frame the server broadcasts is rendered to a 32 px-tall label
 * image and sent to the printer.
 * <p>
 * The server URL can be overridden with the {@code PRINT_SERVER_URL}
 * environment variable. Must be run with {@code -Djava.awt.headless=true}
 * on a headless Pi OS.
 */
public final class RpiLetraTagApp {

    private static final Logger log = LoggerFactory.getLogger(RpiLetraTagApp.class);
    private static final String DEFAULT_SERVER = "wss://w.virit.in/ws/printer";

    public static void main(String[] args) throws Exception {
        URI serverUri = URI.create(
                System.getenv().getOrDefault("PRINT_SERVER_URL", DEFAULT_SERVER));

        LetraTagBleClient printer = new LetraTagBleClient();
        connectWithRetry(printer);

        PrintServerClient wsClient = new PrintServerClient(serverUri, text -> {
            try {
                if (!printer.isConnected()) {
                    log.info("Printer disconnected; reconnecting before print");
                    connectWithRetry(printer);
                }
                BufferedImage rendered = TextLabelRenderer.render(text);
                BufferedImage stretched = LetraTagProtocol.stretchHorizontally(rendered);
                byte[] framed = LetraTagProtocol.buildPrintCommands(stretched);
                printer.sendPrintJob(framed);
            } catch (Exception e) {
                log.warn("Failed to print '{}': {}", text, e.toString(), e);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            wsClient.stop();
            printer.disconnect();
        }));

        wsClient.runForever();
    }

    private static void connectWithRetry(LetraTagBleClient printer) throws InterruptedException {
        long backoffMs = 2000;
        while (true) {
            try {
                printer.connect();
                return;
            } catch (Exception e) {
                log.warn("Printer connect failed, retrying in {}ms: {}", backoffMs, e.toString());
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }

    private RpiLetraTagApp() {
    }
}
