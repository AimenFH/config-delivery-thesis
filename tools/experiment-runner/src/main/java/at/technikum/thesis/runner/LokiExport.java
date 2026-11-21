package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LokiExport {

    public static final String JOB = "runner";
    public static final int BATCH_LINES = 2000;
    public static final int RETRIES = 5;

    public record Entry(long timestampNanos, String line) {
    }

    public record Stream(Map<String, String> labels, List<Entry> entries) {
    }

    public record Loaded(String runId, long fromEpochMillis, long toEpochMillis, int requests, int steps,
                         boolean skipped) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI loki;
    private final HttpClient http;
    private final Duration retryPause;

    public LokiExport(URI loki) {
        this(loki, Duration.ofSeconds(1));
    }

    LokiExport(URI loki, Duration retryPause) {
        this.loki = loki;
        this.retryPause = retryPause;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static int campaign(Path campaignDirectory, URI loki, String grafana) throws Exception {
        ResultWriter store = new ResultWriter(campaignDirectory);
        LokiExport export = new LokiExport(loki);
        List<String> runIds = store.runIds();
        if (runIds.isEmpty()) {
            System.out.println("No runs in " + campaignDirectory.toAbsolutePath());
            return 1;
        }
        for (String runId : runIds) {
            Loaded loaded = export.load(store.read(runId));
            System.out.printf("%s %s: %d requests, %d steps%n", loaded.skipped() ? "Already in Loki" : "Loaded",
                    loaded.runId(), loaded.requests(), loaded.steps());
            System.out.println("  " + dashboardUrl(grafana, loaded));
        }
        return 0;
    }

    public static String dashboardUrl(String grafana, Loaded loaded) {
        return grafana + "/d/run-results/run-results?var-run=" + loaded.runId()
                + "&from=" + loaded.fromEpochMillis() + "&to=" + loaded.toEpochMillis();
    }

    public Loaded load(RunRecord record) throws IOException, InterruptedException {
        List<Stream> streams = streams(record);
        long from = record.runStartedAtEpochMillis() + (long) Math.floor(record.loadStartedAt());
        long to = record.runStartedAtEpochMillis() + (long) Math.ceil(record.runEndAt()) + 3_000;
        int requests = streams.get(0).entries().size();
        int steps = streams.get(1).entries().size();
        if (alreadyLoaded(record.runId(), from, to)) {
            return new Loaded(record.runId(), from, to, requests, steps, true);
        }
        for (Stream stream : streams) {
            for (int start = 0; start < stream.entries().size(); start += BATCH_LINES) {
                int end = Math.min(start + BATCH_LINES, stream.entries().size());
                push(pushBody(stream.labels(), stream.entries().subList(start, end)));
            }
        }
        return new Loaded(record.runId(), from, to, requests, steps, false);
    }

    public static List<Stream> streams(RunRecord record) {
        List<Entry> requests = new ArrayList<>();
        for (Observation observation : record.observations() == null ? List.<Observation>of() : record.observations()) {
            ObjectNode line = JSON.createObjectNode();
            line.put("seq", observation.seq());
            line.put("pod", observation.pod());
            line.put("configVersion", observation.configVersion());
            line.put("status", observation.status());
            line.put("ok", !Availability.failed(observation));
            line.put("latencyMs", round(observation.latencyMillis()));
            line.put("atMs", round(observation.receivedAt()));
            if (observation.error() != null) {
                line.put("error", observation.error());
            }
            requests.add(new Entry(nanos(record, observation.receivedAt()), line.toString()));
        }
        requests.sort(Comparator.comparingLong(Entry::timestampNanos));

        List<Entry> steps = new ArrayList<>();
        for (RunRecord.StepRecord step : record.steps() == null ? List.<RunRecord.StepRecord>of() : record.steps()) {
            ObjectNode line = JSON.createObjectNode();
            line.put("step", actionName(step.action()));
            line.put("index", step.index());
            line.put("exitCode", step.exitCode());
            line.put("durationMs", round(step.finishedAt() - step.startedAt()));
            line.put("atMs", round(step.startedAt()));
            if (step.payload() != null) {
                line.put("configVersion", step.payload().configVersion());
            }
            if (step.detail() != null) {
                line.put("detail", step.detail());
            }
            steps.add(new Entry(nanos(record, step.startedAt()), line.toString()));
            if (step.trigger() != null) {
                ObjectNode trigger = JSON.createObjectNode();
                trigger.put("step", "trigger");
                trigger.put("index", step.index());
                trigger.put("exitCode", step.trigger().exitCode());
                trigger.put("durationMs", round(step.trigger().finishedAt() - step.trigger().startedAt()));
                trigger.put("atMs", round(step.trigger().startedAt()));
                steps.add(new Entry(nanos(record, step.trigger().startedAt()), trigger.toString()));
            }
        }
        steps.sort(Comparator.comparingLong(Entry::timestampNanos));

        ObjectNode run = JSON.createObjectNode();
        run.put("runId", record.runId());
        run.put("campaign", record.campaign());
        run.put("scenario", record.scenario());
        run.put("approach", record.approach());
        run.put("block", record.block());
        run.put("attempt", record.attempt());
        run.put("requests", requests.size());
        run.put("fromEpochMillis", record.runStartedAtEpochMillis() + (long) Math.floor(record.loadStartedAt()));
        run.put("toEpochMillis", record.runStartedAtEpochMillis() + (long) Math.ceil(record.runEndAt()));

        return List.of(
                new Stream(labels(record, "request"), List.copyOf(requests)),
                new Stream(labels(record, "step"), List.copyOf(steps)),
                new Stream(labels(record, "run"),
                        List.of(new Entry(nanos(record, record.loadStartedAt()), run.toString()))));
    }

    public static String pushBody(Map<String, String> labels, List<Entry> entries) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode stream = body.putArray("streams").addObject();
        ObjectNode labelNode = stream.putObject("stream");
        labels.forEach(labelNode::put);
        ArrayNode values = stream.putArray("values");
        for (Entry entry : entries) {
            values.addArray().add(Long.toString(entry.timestampNanos())).add(entry.line());
        }
        return body.toString();
    }

    private static Map<String, String> labels(RunRecord record, String kind) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", JOB);
        labels.put("kind", kind);
        labels.put("campaign", record.campaign());
        labels.put("scenario", record.scenario());
        labels.put("approach", record.approach());
        labels.put("run_id", record.runId());
        return labels;
    }

    private static String actionName(Scenario.Action action) {
        return switch (action) {
            case COMMIT -> "commit";
            case INFRA_DOWN -> "infraDown";
            case INFRA_UP -> "infraUp";
            case DELETE_POD -> "deletePod";
            case DELETE_READY_POD -> "deleteReadyPod";
        };
    }

    private static long nanos(RunRecord record, double millisSinceStart) {
        return record.runStartedAtEpochMillis() * 1_000_000L + Math.round(millisSinceStart * 1_000_000.0);
    }

    private static double round(double millis) {
        return Math.round(millis * 1000.0) / 1000.0;
    }

    private boolean alreadyLoaded(String runId, long fromEpochMillis, long toEpochMillis)
            throws IOException, InterruptedException {
        String query = "{job=\"" + JOB + "\",run_id=\"" + runId + "\"}";
        URI uri = URI.create(base() + "/loki/api/v1/query_range?limit=1"
                + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=" + (fromEpochMillis - 60_000) * 1_000_000L
                + "&end=" + (toEpochMillis + 60_000) * 1_000_000L);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Loki query failed with status " + response.statusCode() + ": " + response.body());
        }
        JsonNode result = JSON.readTree(response.body()).path("data").path("result");
        return result.isArray() && !result.isEmpty();
    }

    private void push(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/loki/api/v1/push"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return;
            }
            if (response.statusCode() != 429 || attempt >= RETRIES) {
                throw new IOException("Loki push failed with status " + response.statusCode() + ": "
                        + response.body().strip());
            }
            Thread.sleep(retryPause.toMillis());
        }
    }

    private String base() {
        String text = loki.toString();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }
}
