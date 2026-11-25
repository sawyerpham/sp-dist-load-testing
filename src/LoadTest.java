import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Sends GET requests to one URL at a fixed rate and records what came back. */
final class LoadTest implements Runnable {
    private final URI url;
    private final int rps;
    private final long[] latencies; // nanoseconds, one slot per request
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private volatile boolean finished;

    LoadTest(URI url, int rps, int seconds) {
        this.url = url;
        this.rps = rps;
        this.latencies = new long[rps * seconds];
    }

    @Override
    public void run() {
        // Redirects are not followed (the default), so a target cannot send load to another host.
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).build();
            long start = System.nanoTime();
            for (int i = 0; i < latencies.length; i++) {
                long due = start + i * 1_000_000_000L / rps;
                for (long wait; (wait = due - System.nanoTime()) > 0; ) LockSupport.parkNanos(wait);
                int slot = i;
                pool.submit(() -> send(client, request, slot));
            }
        } // closing the pool waits for in-flight requests
        finished = true;
    }

    private void send(HttpClient client, HttpRequest request, int slot) {
        long sent = System.nanoTime();
        try {
            if (client.send(request, BodyHandlers.discarding()).statusCode() >= 400) errors.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        }
        latencies[slot] = System.nanoTime() - sent;
        done.incrementAndGet();
    }

    boolean finished() {
        return finished;
    }

    /** Latency percentiles are reported once the test has finished. */
    String toJson() {
        boolean complete = finished;
        long[] sorted = complete ? latencies.clone() : new long[0];
        Arrays.sort(sorted);
        return "{\"finished\":%b,\"requests\":%d,\"errors\":%d,\"p50Ms\":%d,\"p95Ms\":%d}".formatted(
                complete, done.get(), errors.get(), percentile(sorted, 50), percentile(sorted, 95));
    }

    private static long percentile(long[] sorted, int p) {
        return sorted.length == 0 ? 0 : sorted[(sorted.length - 1) * p / 100] / 1_000_000;
    }
}
