package ai.wexa.benchmark.report;

import ai.wexa.benchmark.model.BenchmarkResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

public class ResultWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static Path write(BenchmarkResult result, Path resultsDir) throws IOException {
        resultsDir.toFile().mkdirs();
        String safeName = result.platform.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        String fileName = safeName + "_" + Instant.now().toString().replace(":", "-") + ".json";
        File out = resultsDir.resolve(fileName).toFile();
        MAPPER.writeValue(out, result);
        return out.toPath();
    }
}
