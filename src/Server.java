import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API:
 *   POST /tests?url=http://target/&rps=50&seconds=10   start a test
 *   GET  /tests/{id}                                   read its results
 */
public final class Server {
    static final int MAX_RPS = 500;
    static final int MAX_SECONDS = 60;

    private final Set<String> allowedHosts;
    private final Map<String, LoadTest> tests = new ConcurrentHashMap<>(); // ponytail: in memory, lost on restart
    private LoadTest latest;
    private int nextId = 1;

    Server(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    HttpServer start(String bind, int port) throws IOException {
        var http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/tests", this::handle);
        http.start();
        return http;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            try {
                if (method.equals("POST") && path.equals("/tests")) {
                    create(ex);
                    return;
                }
                var test = method.equals("GET") && path.startsWith("/tests/")
                        ? tests.get(path.substring("/tests/".length()))
                        : null;
                if (test == null) reply(ex, 404, error("not found"));
                else reply(ex, 200, test.toJson());
            } catch (IllegalArgumentException e) {
                reply(ex, 400, error(e.getMessage()));
            }
        }
    }

    private synchronized void create(HttpExchange ex) throws IOException {
        var q = query(ex.getRequestURI().getRawQuery());
        URI url = URI.create(q.getOrDefault("url", ""));
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null)
            throw new IllegalArgumentException("url must be an http or https URL");
        if (!allowedHosts.contains(url.getHost()))
            throw new IllegalArgumentException("host is not in ALLOWED_HOSTS");
        int rps = number(q, "rps", MAX_RPS);
        int seconds = number(q, "seconds", MAX_SECONDS);
        // one test at a time, so the rate cap cannot be multiplied by starting more
        if (latest != null && !latest.finished()) {
            reply(ex, 409, error("a test is already running"));
            return;
        }
        latest = new LoadTest(url, rps, seconds);
        String id = String.valueOf(nextId++);
        tests.put(id, latest);
        Thread.ofVirtual().start(latest);
        reply(ex, 201, "{\"id\":\"" + id + "\"}");
    }

    private static int number(Map<String, String> q, String name, int max) {
        int n = Integer.parseInt(q.getOrDefault(name, "0")); // NumberFormatException -> 400
        if (n < 1 || n > max) throw new IllegalArgumentException(name + " must be between 1 and " + max);
        return n;
    }

    private static Map<String, String> query(String raw) {
        var map = new HashMap<String, String>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) map.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String error(String message) {
        return "{\"error\":\"" + String.valueOf(message).replace("\\", "\\\\").replace("\"", "'") + "\"}";
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }

    public static void main(String[] args) throws IOException {
        var env = System.getenv();
        var hosts = Set.of(env.getOrDefault("ALLOWED_HOSTS", "localhost,127.0.0.1").split(","));
        String bind = env.getOrDefault("BIND", "127.0.0.1"); // the API has no auth: keep it private
        new Server(hosts).start(bind, 8080);
        System.out.println("listening on " + bind + ":8080, allowed targets " + hosts);
    }
}
