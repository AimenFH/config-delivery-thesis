package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ResultWriter {

    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    public ResultWriter(Path directory) {
        this.directory = directory;
    }

    public Path write(RunResult result, List<Observation> observations) throws IOException {
        Files.createDirectories(directory);

        Path data = directory.resolve(result.runId() + ".ndjson");
        try (BufferedWriter out = Files.newBufferedWriter(data)) {
            for (Observation observation : observations) {
                out.write(json.writeValueAsString(observation));
                out.newLine();
            }
        }

        Path meta = directory.resolve(result.runId() + ".meta.json");
        json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(meta.toFile(), result);
        return data;
    }
}