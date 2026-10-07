import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Order microservice: accepts orders and calls inventory-service over HTTP.
 * Shows the patterns students usually struggle with first:
 *  - service-to-service calls with timeouts
 *  - retry with backoff for transient failures
 *  - a tiny circuit breaker so a dead dependency fails fast instead of piling up threads
 *  - a correlation id passed downstream for tracing across services
 *
 * POST /orders?sku=BOOK-1&qty=2
 */
public class OrderService {
    private static final String INVENTORY_URL =
            System.getenv().getOrDefault("INVENTORY_URL", "http://localhost:8081");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();
    private static final CircuitBreaker BREAKER = new CircuitBreaker(3, Duration.ofSeconds(10));

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8082"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\",\"breaker\":\"" + BREAKER.state() + "\"}"));
        server.createContext("/orders", OrderService::handleOrder);
        server.start();
        System.out.println("order-service listening on " + port + ", inventory at " + INVENTORY_URL);
    }

    private static void handleOrder(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"use POST\"}"); return; }
        String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
        String sku = param(q, "sku", null);
        int qty = Integer.parseInt(param(q, "qty", "1"));
        if (sku == null) { send(ex, 400, "{\"error\":\"sku required\"}"); return; }
        String correlationId = UUID.randomUUID().toString();

        if (!BREAKER.allowRequest()) {
            send(ex, 503, "{\"error\":\"inventory unavailable (circuit open)\",\"correlationId\":\"" + correlationId + "\"}");
            return;
        }
        try {
            HttpResponse<String> res = reserveWithRetry(sku, qty, correlationId, 3);
            BREAKER.recordSuccess();
            if (res.statusCode() == 200) {
                send(ex, 201, "{\"orderId\":\"" + UUID.randomUUID() + "\",\"sku\":\"" + sku + "\",\"qty\":" + qty
                        + ",\"correlationId\":\"" + correlationId + "\"}");
            } else {
                // 4xx from inventory is a business answer, not an outage: pass it through.
                send(ex, res.statusCode(), res.body());
            }
        } catch (Exception e) {
            BREAKER.recordFailure();
            send(ex, 503, "{\"error\":\"inventory call failed: " + e.getClass().getSimpleName()
                    + "\",\"correlationId\":\"" + correlationId + "\"}");
        }
    }

    private static HttpResponse<String> reserveWithRetry(String sku, int qty, String cid, int attempts) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(INVENTORY_URL + "/stock/" + sku + "/reserve?qty=" + qty))
                .timeout(Duration.ofSeconds(2))
                .header("X-Correlation-Id", cid)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() < 500) return res;      // only retry server-side errors
                last = new IOException("inventory returned " + res.statusCode());
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(100L * (1L << i));                  // 100ms, 200ms, 400ms backoff
        }
        throw last;
    }

    private static String param(String query, String name, String def) {
        for (String kv : query.split("&")) {
            String[] p = kv.split("=");
            if (p.length == 2 && p[0].equals(name)) return p[1];
        }
        return def;
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Minimal circuit breaker: CLOSED -> OPEN after N failures -> HALF_OPEN after cool-down. */
    static final class CircuitBreaker {
        private final int threshold;
        private final Duration coolDown;
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openedAt = 0;

        CircuitBreaker(int threshold, Duration coolDown) { this.threshold = threshold; this.coolDown = coolDown; }

        synchronized boolean allowRequest() {
            return failures.get() < threshold || System.currentTimeMillis() - openedAt > coolDown.toMillis();
        }
        synchronized void recordSuccess() { failures.set(0); openedAt = 0; }
        synchronized void recordFailure() { if (failures.incrementAndGet() >= threshold) openedAt = System.currentTimeMillis(); }
        String state() {
            if (failures.get() < threshold) return "CLOSED";
            return System.currentTimeMillis() - openedAt > coolDown.toMillis() ? "HALF_OPEN" : "OPEN";
        }
    }
}
