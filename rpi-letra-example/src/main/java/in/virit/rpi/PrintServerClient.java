package in.virit.rpi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal WebSocket client built on {@link java.net.http.WebSocket}. Connects
 * to the print server, delivers every text frame to the supplied consumer,
 * and transparently reconnects with bounded exponential backoff when the
 * connection drops.
 */
class PrintServerClient {

    private static final Logger log = LoggerFactory.getLogger(PrintServerClient.class);

    private final URI serverUri;
    private final Consumer<String> printHandler;
    private final HttpClient httpClient;

    private volatile boolean running;
    private volatile WebSocket webSocket;

    PrintServerClient(URI serverUri, Consumer<String> printHandler) {
        this.serverUri = serverUri;
        this.printHandler = printHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Blocks forever, keeping a WebSocket session alive. Reconnects on any
     * error with exponential backoff (1s -> 30s).
     */
    void runForever() {
        running = true;
        long backoffMs = 1000;
        while (running) {
            try {
                log.info("Connecting to {}", serverUri);
                WebSocket ws = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(serverUri, new Listener())
                        .get(30, TimeUnit.SECONDS);
                this.webSocket = ws;
                log.info("Connected, waiting for print jobs");
                backoffMs = 1000;
                awaitClose(ws);
            } catch (Exception e) {
                log.warn("WebSocket failure: {}", e.toString());
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
    }

    void stop() {
        running = false;
        WebSocket ws = this.webSocket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    /** Parks on a latch held by the listener; released when the socket closes. */
    private void awaitClose(WebSocket ws) throws InterruptedException {
        while (running && !ws.isInputClosed()) {
            Thread.sleep(500);
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                log.info("Received print job ({} chars)", message.length());
                try {
                    printHandler.accept(message);
                } catch (Exception e) {
                    log.warn("Print handler failed: {}", e.toString(), e);
                }
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Server closed connection ({} {})", statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("WebSocket error: {}", error.toString());
        }
    }
}
