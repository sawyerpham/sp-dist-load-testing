import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Set;

/** End-to-end check: starts a local target and the API, runs tests, verifies the numbers. */
public final class SelfCheck {
    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/ok", ex -> { ex.sendResponseHeaders(204, -1); ex.close(); });
        target.createContext("/fail", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        target.start();
        var api = new Server(Set.of("127.0.0.1")).start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + api.getAddress().getPort() + "/tests";
        String to = "http://127.0.0.1:" + target.getAddress().getPort();

        check(post(base + "?url=http://example.com/&rps=1&seconds=1").statusCode() == 400, "disallowed host");
        check(post(base + "?url=" + to + "/ok&rps=100000&seconds=1").statusCode() == 400, "rate cap");

        check(post(base + "?url=" + to + "/ok&rps=50&seconds=1").statusCode() == 201, "create");
        check(post(base + "?url=" + to + "/ok&rps=1&seconds=1").statusCode() == 409, "one at a time");
        String ok = await(base + "/1");
        check(ok.contains("\"requests\":50,\"errors\":0"), ok);

        post(base + "?url=" + to + "/fail&rps=20&seconds=1");
        String failed = await(base + "/2");
        check(failed.contains("\"requests\":20,\"errors\":20"), failed);

        api.stop(0);
        target.stop(0);
        System.out.println("PASS");
    }

    static HttpResponse<String> post(String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, BodyHandlers.ofString());
    }

    static String await(String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).build();
        for (int i = 0; i < 100; i++) {
            String body = client.send(request, BodyHandlers.ofString()).body();
            if (body.contains("\"finished\":true")) return body;
            Thread.sleep(100);
        }
        throw new AssertionError("test did not finish");
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
