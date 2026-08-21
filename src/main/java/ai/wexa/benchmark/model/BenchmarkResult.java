package ai.wexa.benchmark.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Generic bag of metrics for one platform run. Serialized straight to JSON. */
public class BenchmarkResult {
    public String platform;
    public String startedAtUtc;
    public Map<String, Object> environment = new LinkedHashMap<>();
    public Map<String, Object> loading = new LinkedHashMap<>();
    public Map<String, Object> traversals = new LinkedHashMap<>();
    public Map<String, Object> lookups = new LinkedHashMap<>();
    public Map<String, Object> aggregations = new LinkedHashMap<>();
    public Map<String, Object> mixedWorkload = new LinkedHashMap<>();
    public Map<String, Object> footprint = new LinkedHashMap<>();
    public java.util.List<String> caveats = new java.util.ArrayList<>();
}
