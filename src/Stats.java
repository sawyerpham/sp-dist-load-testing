import java.util.Map;
import java.util.TreeMap;

/**
 * Results of one test, or of several added together.
 * Latencies are kept as a histogram (milliseconds -> count) because percentiles from
 * different workers cannot be averaged, but their histograms can be summed.
 */
final class Stats {
    boolean finished = true;
    long requests;
    long errors;
    int lostWorkers;
    final TreeMap<Long, Long> latencies = new TreeMap<>();

    void add(Stats other) {
        finished &= other.finished;
        requests += other.requests;
        errors += other.errors;
        lostWorkers += other.lostWorkers;
        other.latencies.forEach((ms, count) -> latencies.merge(ms, count, Long::sum));
    }

    long percentile(int p) {
        long total = latencies.values().stream().mapToLong(Long::longValue).sum();
        long rank = (total - 1) * p / 100;
        long seen = 0;
        for (var e : latencies.entrySet()) {
            seen += e.getValue();
            if (seen > rank) return e.getKey();
        }
        return 0;
    }

    String toJson() {
        return "{\"finished\":%b,\"requests\":%d,\"errors\":%d,\"p50Ms\":%d,\"p95Ms\":%d,\"lostWorkers\":%d}"
                .formatted(finished, requests, errors, percentile(50), percentile(95), lostWorkers);
    }

    /** Worker-to-controller format: "finished requests errors", then one "ms count" line per bucket. */
    String encode() {
        var out = new StringBuilder("%b %d %d\n".formatted(finished, requests, errors));
        for (Map.Entry<Long, Long> e : latencies.entrySet())
            out.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        return out.toString();
    }

    static Stats decode(String text) {
        var lines = text.split("\n");
        var head = lines[0].split(" ");
        var stats = new Stats();
        stats.finished = Boolean.parseBoolean(head[0]);
        stats.requests = Long.parseLong(head[1]);
        stats.errors = Long.parseLong(head[2]);
        for (int i = 1; i < lines.length; i++) {
            var bucket = lines[i].split(" ");
            stats.latencies.put(Long.parseLong(bucket[0]), Long.parseLong(bucket[1]));
        }
        return stats;
    }
}
