import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates load. REST API:
 *   POST /tests?url=http://target/&rps=50&seconds=10   start a test
 *   GET  /tests/{id}                                   results as JSON
 *   GET  /tests/{id}/raw                               results for the controller (see Stats.encode)
 */
public final class Worker {
    static final int MAX_RPS = 500;
    static final int MAX_SECONDS = 60;

    private final Set<String> allowedHosts;
    private final Map<String, LoadTest> tests = new ConcurrentHashMap<>(); // ponytail: in memory, lost on restart
    private LoadTest latest;

    Worker(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    HttpServer start(String bind, int port) throws IOException {
        return Http.serve(bind, port, this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String[] path = ex.getRequestURI().getPath().split("/"); // "", "tests", id, "raw"
        if (method.equals("POST") && path.length == 2) {
            create(ex);
            return;
        }
        var test = method.equals("GET") && path.length > 2 ? tests.get(path[2]) : null;
        if (test != null && path.length == 3) Http.reply(ex, 200, test.stats().toJson());
        else if (test != null && path.length == 4 && path[3].equals("raw")) Http.reply(ex, 200, test.stats().encode());
        else Http.error(ex, 404, "not found");
    }

    private synchronized void create(HttpExchange ex) throws IOException {
        var q = Http.query(ex);
        URI url = URI.create(q.getOrDefault("url", ""));
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null)
            throw new IllegalArgumentException("url must be an http or https URL");
        if (!allowedHosts.contains(url.getHost()))
            throw new IllegalArgumentException("host is not in ALLOWED_HOSTS");
        int rps = Http.number(q, "rps", MAX_RPS);
        int seconds = Http.number(q, "seconds", MAX_SECONDS);
        // one test at a time, so the rate cap cannot be multiplied by starting more
        if (latest != null && !latest.finished()) {
            Http.error(ex, 409, "a test is already running");
            return;
        }
        latest = new LoadTest(url, rps, seconds);
        // random, so an id can never point at a different test after a restart
        String id = UUID.randomUUID().toString();
        tests.put(id, latest);
        Thread.ofVirtual().start(latest);
        Http.reply(ex, 201, "{\"id\":\"" + id + "\"}");
    }

    public static void main(String[] args) throws IOException {
        var env = System.getenv();
        var hosts = Set.of(env.getOrDefault("ALLOWED_HOSTS", "localhost,127.0.0.1").split(","));
        String bind = env.getOrDefault("BIND", "127.0.0.1"); // the API has no auth: keep it private
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        new Worker(hosts).start(bind, port);
        System.out.println("worker on " + bind + ":" + port + ", allowed targets " + hosts);
    }
}
