import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory microservice: owns stock data and exposes it over HTTP.
 * GET  /health                 -> {"status":"UP"}
 * GET  /stock/{sku}            -> {"sku":"...","available":N}
 * POST /stock/{sku}/reserve?qty=N -> 200 reserved | 409 not enough stock
 */
public class InventoryService {
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>(Map.of(
            "BOOK-1", 10, "PEN-7", 3, "LAPTOP-2", 0));

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/stock/", InventoryService::handleStock);
        server.start();
        System.out.println("inventory-service listening on " + port);
    }

    private static void handleStock(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/"); // ["", "stock", sku, (reserve)]
        if (parts.length < 3) { send(ex, 400, "{\"error\":\"missing sku\"}"); return; }
        String sku = parts[2];
        if (!STOCK.containsKey(sku)) { send(ex, 404, "{\"error\":\"unknown sku\"}"); return; }

        if (parts.length == 3 && "GET".equals(ex.getRequestMethod())) {
            send(ex, 200, "{\"sku\":\"" + sku + "\",\"available\":" + STOCK.get(sku) + "}");
        } else if (parts.length == 4 && "reserve".equals(parts[3]) && "POST".equals(ex.getRequestMethod())) {
            int qty = parseQty(ex.getRequestURI().getQuery());
            // compute() makes check-and-decrement atomic per SKU, so concurrent orders can't oversell.
            boolean[] ok = {false};
            STOCK.compute(sku, (k, v) -> { if (v >= qty) { ok[0] = true; return v - qty; } return v; });
            if (ok[0]) send(ex, 200, "{\"sku\":\"" + sku + "\",\"reserved\":" + qty + "}");
            else send(ex, 409, "{\"error\":\"insufficient stock\"}");
        } else {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
        }
    }

    private static int parseQty(String query) {
        if (query != null) for (String kv : query.split("&")) {
            String[] p = kv.split("=");
            if (p.length == 2 && p[0].equals("qty")) return Math.max(1, Integer.parseInt(p[1]));
        }
        return 1;
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
