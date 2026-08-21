package ai.wexa.benchmark.workload;

import ai.wexa.benchmark.model.BenchmarkResult;
import ai.wexa.benchmark.platform.GraphClient;
import ai.wexa.benchmark.stats.LatencyStats;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The same workload, run identically against whichever GraphClient is passed
 * in, per the assignment's "same logical queries" rule.
 *
 * Defaults (overridable via env / constructor args): 100 iterations per read
 * workload after a warm-up pass, 20 concurrent clients for 20 seconds on the
 * mixed workload with an 80/20 read/write mix.
 */
public class WorkloadRunner {

    private static final int WARMUP_ITERATIONS = 10;
    private final int iterations;
    private final int mixedConcurrency;
    private final int mixedDurationSeconds;
    private final Random random = new Random(42); // fixed seed: same "random" start nodes across platforms

    public WorkloadRunner(int iterations, int mixedConcurrency, int mixedDurationSeconds) {
        this.iterations = iterations;
        this.mixedConcurrency = mixedConcurrency;
        this.mixedDurationSeconds = mixedDurationSeconds;
    }

    public void run(GraphClient client, List<Long> nodeIds, BenchmarkResult result) {
        List<Long> startNodes = sampleStartNodes(nodeIds, iterations + WARMUP_ITERATIONS);

        for (int hops = 1; hops <= 3; hops++) {
            LatencyStats stats = timeHopWorkload(client, startNodes, hops);
            result.traversals.put(hops + "_hop", stats.summarize());
        }

        LatencyStats pointLookup = new LatencyStats();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) client.pointLookup(startNodes.get(i));
        for (int i = WARMUP_ITERATIONS; i < startNodes.size(); i++) {
            long t0 = System.nanoTime();
            client.pointLookup(startNodes.get(i));
            pointLookup.record((System.nanoTime() - t0) / 1_000_000.0);
        }
        result.lookups.put("point_lookup", pointLookup.summarize());

        LatencyStats rangeLookup = new LatencyStats();
        long minId = nodeIds.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxId = nodeIds.stream().mapToLong(Long::longValue).max().orElse(0);
        long span = Math.max(1, (maxId - minId) / 100);
        for (int i = 0; i < iterations + WARMUP_ITERATIONS; i++) {
            long lo = minId + random.nextInt((int) Math.max(1, maxId - minId - span));
            long t0 = System.nanoTime();
            client.indexedRangeLookup(lo, lo + span);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (i >= WARMUP_ITERATIONS) rangeLookup.record(ms);
        }
        result.lookups.put("indexed_range_lookup", rangeLookup.summarize());

        LatencyStats agg = new LatencyStats();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) client.aggregationCountByBucket();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            client.aggregationCountByBucket();
            agg.record((System.nanoTime() - t0) / 1_000_000.0);
        }
        result.aggregations.put("count_by_id_bucket", agg.summarize());

        result.mixedWorkload.putAll(runMixedWorkload(client, nodeIds));
        result.footprint.putAll(client.footprint());
    }

    private LatencyStats timeHopWorkload(GraphClient client, List<Long> startNodes, int hops) {
        for (int i = 0; i < WARMUP_ITERATIONS && i < startNodes.size(); i++) {
            client.traverse(startNodes.get(i), hops); // untimed warm-up
        }
        LatencyStats stats = new LatencyStats();
        for (int i = WARMUP_ITERATIONS; i < startNodes.size(); i++) {
            long t0 = System.nanoTime();
            client.traverse(startNodes.get(i), hops);
            stats.record((System.nanoTime() - t0) / 1_000_000.0);
        }
        return stats;
    }

    private List<Long> sampleStartNodes(List<Long> nodeIds, int count) {
        List<Long> shuffled = new java.util.ArrayList<>(nodeIds);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    /** 80/20 read/write mix across a fixed pool of concurrent clients for a fixed duration. */
    private Map<String, Object> runMixedWorkload(GraphClient client, List<Long> nodeIds) {
        ExecutorService pool = Executors.newFixedThreadPool(mixedConcurrency);
        AtomicLong ops = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        LatencyStats stats = new LatencyStats();
        long deadline = System.currentTimeMillis() + mixedDurationSeconds * 1000L;

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int c = 0; c < mixedConcurrency; c++) {
            futures.add(pool.submit(() -> {
                Random r = new Random();
                while (System.currentTimeMillis() < deadline) {
                    long id = nodeIds.get(r.nextInt(nodeIds.size()));
                    long t0 = System.nanoTime();
                    try {
                        if (r.nextInt(100) < 80) {
                            client.pointLookup(id);
                        } else {
                            client.mixedWorkloadUnit(id, System.nanoTime());
                        }
                        synchronized (stats) {
                            stats.record((System.nanoTime() - t0) / 1_000_000.0);
                        }
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        futures.forEach(f -> { try { f.get(); } catch (Exception ignored) {} });
        pool.shutdown();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("concurrency", mixedConcurrency);
        out.put("duration_seconds", mixedDurationSeconds);
        out.put("read_write_mix", "80/20");
        out.put("total_ops", ops.get());
        out.put("errors", errors.get());
        out.put("throughput_ops_per_sec", Math.round((ops.get() / (double) mixedDurationSeconds) * 100.0) / 100.0);
        out.put("latency", stats.summarize());
        return out;
    }
}
