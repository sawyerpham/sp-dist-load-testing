import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The few HTTP helpers shared by the worker and the controller. */
final class Http {
    private Http() {}

    static HttpServer serve(String bind, int port, HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/tests", ex -> {
            try (ex) {
                try {
                    handler.handle(ex);
                } catch (IllegalArgumentException e) {
                    error(ex, 400, e.getMessage());
                }
            }
        });
        server.start();
        return server;
    }

    static Map<String, String> query(HttpExchange ex) {
        var map = new HashMap<String, String>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) map.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    static int number(Map<String, String> query, String name, int max) {
        int n = Integer.parseInt(query.getOrDefault(name, "0")); // NumberFormatException -> 400
        if (n < 1 || n > max) throw new IllegalArgumentException(name + " must be between 1 and " + max);
        return n;
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        String safe = String.valueOf(message).replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
        reply(ex, status, "{\"error\":\"" + safe + "\"}");
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", body.startsWith("{") ? "application/json" : "text/plain");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
