package at.technikum.thesis.runner;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class ResultWriter implements RunStore {

    public static final String OBSERVATIONS_SUFFIX = ".ndjson";
    public static final String RESOURCES_SUFFIX = ".resources.ndjson";
    public static final String CLUSTER_SUFFIX = ".cluster.json";
    public static final String META_SUFFIX = ".meta.json";

    private static final List<String> RAW_FIELDS = List.of("observations", "resources", "cluster");
    private static final List<String> EVALUATION_FIELDS = List.of("tCs", "t0", "verdict", "invalidReasons", "flags");

    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectWriter pretty = json.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n")));

    public ResultWriter(Path directory) {
        this.directory = directory;
    }

    public Path write(RunResult result, List<Observation> observations) throws IOException {
        Files.createDirectories(directory);

        Path data = directory.resolve(result.runId() + OBSERVATIONS_SUFFIX);
        try (BufferedWriter out = Files.newBufferedWriter(data)) {
            for (Observation observation : observations) {
                out.write(json.writeValueAsString(observation));
                out.newLine();
            }
        }

        Path meta = directory.resolve(result.runId() + META_SUFFIX);
        json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(meta.toFile(), result);
        return data;
    }

    @Override
    public void save(RunRecord record, RunMetrics metrics) throws IOException {
        Files.createDirectories(directory);
        writeLines(directory.resolve(record.runId() + OBSERVATIONS_SUFFIX), record.observations());
        writeLines(directory.resolve(record.runId() + RESOURCES_SUFFIX), record.resources());
        writeText(directory.resolve(record.runId() + CLUSTER_SUFFIX),
                pretty.writeValueAsString(record.cluster()) + "\n");

        ObjectNode meta = json.valueToTree(record);
        meta.remove(RAW_FIELDS);
        meta.put("tCs", record.tCs());
        meta.put("t0", record.t0());
        meta.put("verdict", metrics.verdict().name());
        meta.set("invalidReasons", json.valueToTree(metrics.invalidReasons()));
        meta.set("flags", json.valueToTree(new TreeSet<>(metrics.flags())));
        writeText(directory.resolve(record.runId() + META_SUFFIX), pretty.writeValueAsString(meta) + "\n");
    }

    public RunRecord read(String runId) throws IOException {
        ObjectNode meta = (ObjectNode) json.readTree(directory.resolve(runId + META_SUFFIX).toFile());
        meta.remove(EVALUATION_FIELDS);
        meta.set("observations", readLines(directory.resolve(runId + OBSERVATIONS_SUFFIX)));
        meta.set("resources", readLines(directory.resolve(runId + RESOURCES_SUFFIX)));
        meta.set("cluster", json.readTree(directory.resolve(runId + CLUSTER_SUFFIX).toFile()));
        return json.treeToValue(meta, RunRecord.class);
    }

    public List<String> runIds() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(META_SUFFIX))
                    .map(name -> name.substring(0, name.length() - META_SUFFIX.length()))
                    .sorted()
                    .toList();
        }
    }

    private void writeLines(Path file, List<?> values) throws IOException {
        StringBuilder text = new StringBuilder();
        if (values != null) {
            for (Object value : values) {
                text.append(json.writeValueAsString(value)).append('\n');
            }
        }
        writeText(file, text.toString());
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private ArrayNode readLines(Path file) throws IOException {
        ArrayNode lines = json.createArrayNode();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonNode node = json.readTree(line);
                lines.add(node);
            }
        }
        return lines;
    }
}
