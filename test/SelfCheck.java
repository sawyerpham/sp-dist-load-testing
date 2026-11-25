import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** End-to-end check on localhost: a target, three workers and a controller. */
public final class SelfCheck {
    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // merging: percentiles come from the summed histogram, not from averaging
        var fast = Stats.decode("true 99 0\n1 99\n");
        fast.add(Stats.decode("true 1 1\n500 1\n"));
        check(fast.requests == 100 && fast.errors == 1 && fast.percentile(50) == 1 && fast.percentile(100) == 500,
                fast.toJson());
        check(Arrays.equals(Controller.split(50, 3), new int[] {17, 17, 16}), "split");
        check(Arrays.equals(Controller.split(2, 3), new int[] {1, 1}), "split smaller than fleet");

        var hits = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/ok", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close(); });
        target.createContext("/fail", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        target.start();
        String to = "http://127.0.0.1:" + target.getAddress().getPort();

        var workers = new ArrayList<HttpServer>();
        var addresses = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var worker = new Worker(Set.of("127.0.0.1")).start("127.0.0.1", 0);
            workers.add(worker);
            addresses.add(address(worker));
        }
        var controller = new Controller(List.copyOf(addresses)).start("127.0.0.1", 0);
        String api = address(controller) + "/tests";

        // limits still apply through the controller
        check(post(api + "?url=http://example.com/&rps=3&seconds=1").statusCode() == 400, "disallowed host");
        check(post(api + "?url=" + to + "/ok&rps=100000&seconds=1").statusCode() == 400, "rate cap");

        // one test, split three ways and added back together
        var created = post(api + "?url=" + to + "/ok&rps=50&seconds=1");
        check(created.statusCode() == 201 && created.body().contains("\"workers\":3"), created.body());
        check(post(api + "?url=" + to + "/ok&rps=3&seconds=1").statusCode() == 409, "one at a time");
        String ok = await(api + "/" + id(created));
        check(ok.contains("\"requests\":50,\"errors\":0") && ok.contains("\"lostWorkers\":0"), ok);
        check(hits.get() == 50, "target saw " + hits.get());

        var failing = post(api + "?url=" + to + "/fail&rps=20&seconds=1");
        String failed = await(api + "/" + id(failing));
        check(failed.contains("\"requests\":20,\"errors\":20"), failed);

        // a dead worker is reported, both mid-test and when starting a new one
        var last = post(api + "?url=" + to + "/ok&rps=3&seconds=1");
        workers.get(2).stop(0);
        String partial = get(api + "/" + id(last));
        check(partial.contains("\"lostWorkers\":1") && partial.contains("\"finished\":false"), partial);
        Thread.sleep(1500);
        check(post(api + "?url=" + to + "/ok&rps=3&seconds=1").statusCode() == 502, "unreachable worker");

        controller.stop(0);
        workers.forEach(w -> w.stop(0));
        target.stop(0);
        System.out.println("PASS");
    }

    static String address(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String id(HttpResponse<String> created) {
        var match = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
        check(match.find(), created.body());
        return match.group(1);
    }

    static HttpResponse<String> post(String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, BodyHandlers.ofString());
    }

    static String get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofString()).body();
    }

    static String await(String url) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = get(url);
            if (body.contains("\"finished\":true")) return body;
            Thread.sleep(100);
        }
        throw new AssertionError("test did not finish");
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
