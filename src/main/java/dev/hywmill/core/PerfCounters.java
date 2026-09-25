package dev.hywmill.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cheap timing counters for HywMill work slices (count, total, max per named slice). Owned by
 * {@link HywMillRuntime}; reset per server. Server thread only for writes.
 */
public final class PerfCounters {
    private static final int RESERVOIR = 1024;

    private static final class Stat {
        long count;
        long totalNanos;
        long maxNanos;
        final long[] recent = new long[RESERVOIR];

        long p99() {
            int n = (int) Math.min(count, RESERVOIR);
            if (n == 0) {
                return 0;
            }
            long[] copy = java.util.Arrays.copyOf(recent, n);
            java.util.Arrays.sort(copy);
            return copy[Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1)];
        }
    }

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();

    public long start() {
        return System.nanoTime();
    }

    public void stop(String slice, long startNanos) {
        long d = System.nanoTime() - startNanos;
        Stat s = stats.computeIfAbsent(slice, k -> new Stat());
        s.recent[(int) (s.count % RESERVOIR)] = d;
        s.count++;
        s.totalNanos += d;
        if (d > s.maxNanos) {
            s.maxNanos = d;
        }
    }

    public void reset() {
        stats.clear();
    }

    /** slice -> "n=…, mean=…µs, max=…µs, p99=…µs (last 1024 samples), total=…ms" */
    public Map<String, String> report() {
        Map<String, String> out = new TreeMap<>();
        stats.forEach((k, s) -> out.put(k, String.format("n=%d mean=%.1fus max=%.1fus p99=%.1fus total=%.1fms",
                s.count, s.count == 0 ? 0.0 : s.totalNanos / 1000.0 / s.count, s.maxNanos / 1000.0, s.p99() / 1000.0, s.totalNanos / 1e6)));
        return out;
    }
}
