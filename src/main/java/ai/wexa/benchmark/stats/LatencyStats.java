package ai.wexa.benchmark.stats;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Collects raw latency samples (in milliseconds, as doubles) for one workload
 * and reduces them to the percentiles the assignment asks for (p50 / p95),
 * plus mean, min, max and count for sanity-checking.
 *
 * Uses nearest-rank percentile on a sorted copy of the samples. Simple and
 * defensible for report purposes; not trying to be a full HDR histogram.
 */
public class LatencyStats {

    private final List<Double> samplesMs = new java.util.ArrayList<>();

    public void record(double ms) {
        samplesMs.add(ms);
    }

    public int count() {
        return samplesMs.size();
    }

    public Map<String, Object> summarize() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (samplesMs.isEmpty()) {
            out.put("count", 0);
            out.put("note", "no samples recorded");
            return out;
        }
        double[] sorted = samplesMs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        out.put("count", sorted.length);
        out.put("min_ms", round(sorted[0]));
        out.put("max_ms", round(sorted[sorted.length - 1]));
        out.put("mean_ms", round(Arrays.stream(sorted).average().orElse(0)));
        out.put("p50_ms", round(percentile(sorted, 50)));
        out.put("p95_ms", round(percentile(sorted, 95)));
        out.put("p99_ms", round(percentile(sorted, 99)));
        return out;
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        int rank = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        rank = Math.max(0, Math.min(sorted.length - 1, rank));
        return sorted[rank];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
