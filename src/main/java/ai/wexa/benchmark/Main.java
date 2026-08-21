package ai.wexa.benchmark;

import ai.wexa.benchmark.config.Config;
import ai.wexa.benchmark.loader.DatasetLoader;
import ai.wexa.benchmark.model.BenchmarkResult;
import ai.wexa.benchmark.platform.*;
import ai.wexa.benchmark.report.ResultWriter;
import ai.wexa.benchmark.workload.WorkloadRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Usage:
 *   java -jar cognodb-benchmark.jar --platform=cognodb --dataset=data/ca-HepPh.txt.gz
 *   java -jar cognodb-benchmark.jar --platform=aura     --dataset=data/ca-HepPh.txt.gz
 *   java -jar cognodb-benchmark.jar --platform=memgraph --dataset=data/ca-HepPh.txt.gz
 *   java -jar cognodb-benchmark.jar --platform=arango   --dataset=data/ca-HepPh.txt.gz
 *   java -jar cognodb-benchmark.jar --platform=nebula   --dataset=data/ca-HepPh.txt.gz
 *
 * Optional flags: --iterations=100 --concurrency=20 --duration=20 --batchSize=1000
 * See scripts/run_all.sh to run every platform back to back.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);
        String platform = flags.get("platform");
        if (platform == null) {
            System.err.println("Missing --platform=cognodb|aura|memgraph|arango|nebula");
            System.exit(1);
        }

        int iterations = Integer.parseInt(flags.getOrDefault("iterations", "100"));
        int concurrency = Integer.parseInt(flags.getOrDefault("concurrency", "20"));
        int duration = Integer.parseInt(flags.getOrDefault("duration", "20"));
        int batchSize = Integer.parseInt(flags.getOrDefault("batchSize", "1000"));
        Path dataset = Path.of(flags.getOrDefault("dataset", "data/ca-HepPh.txt.gz"));

        GraphClient client = buildClient(platform);
        BenchmarkResult result = new BenchmarkResult();
        result.platform = client.platformName();
        result.startedAtUtc = Instant.now().toString();
        result.environment.put("iterations_per_read_workload", iterations);
        result.environment.put("mixed_workload_concurrency", concurrency);
        result.environment.put("mixed_workload_duration_seconds", duration);
        result.environment.put("load_batch_size", batchSize);
        result.environment.put("dataset_file", dataset.toString());

        System.out.println("=== " + client.platformName() + " ===");
        System.out.println("Connecting...");
        client.connect();

        try {
            System.out.println("Loading dataset: " + dataset);
            var ds = DatasetLoader.load(dataset);
            result.environment.put("node_count", ds.nodeIds().size());
            result.environment.put("relationship_count", ds.edges().size());

            System.out.println("Clearing any previous data...");
            client.clearData();

            System.out.println("Creating indexes...");
            client.ensureIndexes();

            System.out.println("Loading " + ds.nodeIds().size() + " nodes / " + ds.edges().size() + " edges...");
            long loadMs = client.loadGraph(ds.nodeIds(), ds.edges(), batchSize);
            double seconds = loadMs / 1000.0;
            result.loading.put("total_wall_clock_ms", loadMs);
            result.loading.put("nodes_per_second", Math.round(ds.nodeIds().size() / Math.max(seconds, 0.001) * 100.0) / 100.0);
            result.loading.put("relationships_per_second", Math.round(ds.edges().size() / Math.max(seconds, 0.001) * 100.0) / 100.0);
            System.out.println("Load complete in " + loadMs + " ms");

            System.out.println("Running workload suite (iterations=" + iterations + ")...");
            WorkloadRunner runner = new WorkloadRunner(iterations, concurrency, duration);
            runner.run(client, ds.nodeIds(), result);

            Path out = ResultWriter.write(result, Path.of("results"));
            System.out.println("Results written to: " + out);

        } catch (Exception e) {
            result.caveats.add("Run failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            ResultWriter.write(result, Path.of("results"));
            throw e;
        } finally {
            client.close();
        }
    }

    private static GraphClient buildClient(String platform) {
        return switch (platform.toLowerCase()) {
            case "cognodb" -> new CognoDbClient();
            case "aura", "auradb", "neo4j" -> new AuraDbClient();
            case "memgraph" -> new MemgraphClient();
            case "arango", "arangodb" -> new ArangoDbClient();
            case "nebula" -> new NebulaClient();
            default -> throw new IllegalArgumentException("Unknown platform: " + platform);
        };
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] kv = arg.substring(2).split("=", 2);
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }
}
