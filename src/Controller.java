import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Splits one test across a fixed list of workers and adds their results together. REST API:
 *   POST /tests?url=http://target/&rps=800&seconds=10
 *   GET  /tests/{id}
 * Each worker still enforces its own host allow-list and rate cap.
 */
public final class Controller {
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]+)\"");

    private final List<String> workers;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, List<Part>> tests = new ConcurrentHashMap<>(); // ponytail: in memory

    /** One worker's share of a test. */
    private record Part(String worker, String id) {}

    Controller(List<String> workers) {
        this.workers = workers;
    }

    HttpServer start(String bind, int port) throws IOException {
        return Http.serve(bind, port, this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String[] path = ex.getRequestURI().getPath().split("/");
        if (method.equals("POST") && path.length == 2) {
            create(ex);
            return;
        }
        var parts = method.equals("GET") && path.length == 3 ? tests.get(path[2]) : null;
        if (parts == null) Http.error(ex, 404, "not found");
        else Http.reply(ex, 200, collect(parts).toJson());
    }

    /** Splits total as evenly as possible; never returns a share of zero. */
    static int[] split(int total, int ways) {
        int[] shares = new int[Math.min(total, ways)];
        for (int i = 0; i < shares.length; i++)
            shares[i] = total / shares.length + (i < total % shares.length ? 1 : 0);
        return shares;
    }

    private synchronized void create(HttpExchange ex) throws IOException {
        var q = Http.query(ex);
        int rps = Http.number(q, "rps", Worker.MAX_RPS * workers.size());
        int seconds = Http.number(q, "seconds", Worker.MAX_SECONDS);
        String url = URLEncoder.encode(q.getOrDefault("url", ""), StandardCharsets.UTF_8);

        int[] shares = split(rps, workers.size());
        var parts = new ArrayList<Part>();
        for (int i = 0; i < shares.length; i++) {
            String worker = workers.get(i);
            var request = HttpRequest.newBuilder(
                    URI.create(worker + "/tests?url=" + url + "&rps=" + shares[i] + "&seconds=" + seconds))
                    .POST(HttpRequest.BodyPublishers.noBody());
            // ponytail: if a later worker fails, earlier ones run on until their time is up (60s at most)
            var response = send(request);
            if (response == null) {
                Http.error(ex, 502, "worker unreachable: " + worker);
                return;
            }
            var id = ID.matcher(response.body());
            if (response.statusCode() != 201 || !id.find()) {
                Http.reply(ex, response.statusCode(), response.body()); // pass the worker's reason through
                return;
            }
            parts.add(new Part(worker, id.group(1)));
        }
        String id = UUID.randomUUID().toString();
        tests.put(id, parts);
        Http.reply(ex, 201, "{\"id\":\"" + id + "\",\"workers\":" + parts.size() + "}");
    }

    private Stats collect(List<Part> parts) {
        var total = new Stats();
        for (var part : parts) {
            var response = send(HttpRequest.newBuilder(URI.create(part.worker() + "/tests/" + part.id() + "/raw")));
            if (response != null && response.statusCode() == 200) {
                total.add(Stats.decode(response.body()));
            } else {
                total.lostWorkers++;
                total.finished = false; // its share is unknown; recovery is a later step
            }
        }
        return total;
    }

    /** Returns null if the worker cannot be reached. */
    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.timeout(Duration.ofSeconds(3)).build(), BodyHandlers.ofString());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static void main(String[] args) throws IOException {
        var env = System.getenv();
        var workers = Arrays.asList(env.getOrDefault("WORKERS", "http://127.0.0.1:8081").split(","));
        String bind = env.getOrDefault("BIND", "127.0.0.1"); // the API has no auth: keep it private
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        new Controller(workers).start(bind, port);
        System.out.println("controller on " + bind + ":" + port + ", workers " + workers);
    }
}
