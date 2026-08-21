package ai.wexa.benchmark.loader;

import ai.wexa.benchmark.model.Edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Parses the SNAP ca-HepPh.txt(.gz) edge list: two whitespace/tab-separated
 * node ids per non-comment line ("# ..." lines are the SNAP header/comments
 * and are skipped). See data/download_dataset.sh for how to fetch it.
 */
public class DatasetLoader {

    public record Dataset(List<Long> nodeIds, List<Edge> edges) {}

    public static Dataset load(Path path) throws IOException {
        Set<Long> nodeSet = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();

        try (BufferedReader br = openReader(path)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                long src = Long.parseLong(parts[0]);
                long dst = Long.parseLong(parts[1]);
                nodeSet.add(src);
                nodeSet.add(dst);
                edges.add(new Edge(src, dst));
            }
        }
        return new Dataset(new ArrayList<>(nodeSet), edges);
    }

    private static BufferedReader openReader(Path path) throws IOException {
        if (path.toString().endsWith(".gz")) {
            return new BufferedReader(new java.io.InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(path))));
        }
        return Files.newBufferedReader(path);
    }
}
