package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static at.technikum.thesis.runner.TestRuns.A;
import static at.technikum.thesis.runner.TestRuns.cleanS1;
import static at.technikum.thesis.runner.TestRuns.commit;
import static at.technikum.thesis.runner.TestRuns.failure;
import static at.technikum.thesis.runner.TestRuns.replace;
import static at.technikum.thesis.runner.TestRuns.samples;
import static at.technikum.thesis.runner.TestRuns.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LokiExportTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
    }

    private static final long RUN_START = 1794769200000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<Integer> pushStatuses = new ArrayList<>();
    private String queryAnswer = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}";
    private HttpServer server;
    private URI loki;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            pushes.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status;
            synchronized (pushStatuses) {
                status = pushStatuses.isEmpty() ? 204 : pushStatuses.remove(0);
            }
            answer(exchange, status, status == 204 ? "" : "refused in the test");
        });
        server.createContext("/loki/api/v1/query_range", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            answer(exchange, 200, queryAnswer);
        });
        server.start();
        loki = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void a_run_becomes_three_streams_with_the_labels_of_the_run() {
        List<LokiExport.Stream> streams = LokiExport.streams(TestRuns.s1(cleanS1()));

        assertEquals(3, streams.size());
        assertEquals(Map.of("job", "runner", "kind", "request", "campaign", "20261115-200000_test",
                "scenario", "s1-valid-change", "approach", "way-a", "run_id", "run-1"), streams.get(0).labels());
        assertEquals("step", streams.get(1).labels().get("kind"));
        assertEquals("run", streams.get(2).labels().get("kind"));
        assertEquals(12_000, streams.get(0).entries().size());
        assertEquals(1, streams.get(1).entries().size());
        assertEquals(1, streams.get(2).entries().size());
    }

    @Test
    void a_request_is_stamped_with_the_wall_clock_time_at_which_its_answer_was_received() throws Exception {
        List<LokiExport.Entry> requests = LokiExport.streams(TestRuns.s1(cleanS1())).get(0).entries();

        LokiExport.Entry first = requests.get(0);
        LokiExport.Entry switched = requests.get(4_750);
        assertEquals((RUN_START + 2) * NANOS_PER_MILLI, first.timestampNanos());
        assertEquals("{\"seq\":0,\"pod\":\"probe-service-a\",\"configVersion\":\"v1\",\"status\":200,\"ok\":true,"
                + "\"latencyMs\":2.0,\"atMs\":2.0}", first.line());
        assertEquals((RUN_START + 95_002) * NANOS_PER_MILLI, switched.timestampNanos());
        assertEquals("v2", Fixtures.JSON.readTree(switched.line()).get("configVersion").asText());
    }

    @Test
    void the_requests_are_sorted_by_receive_time_and_a_failed_request_carries_its_error() throws Exception {
        List<LokiExport.Entry> requests = LokiExport.streams(TestRuns.s1(replace(cleanS1(), failure(100))))
                .get(0).entries();

        for (int i = 1; i < requests.size(); i++) {
            assertTrue(requests.get(i - 1).timestampNanos() <= requests.get(i).timestampNanos(), "entry " + i);
        }
        JsonNode failed = Fixtures.JSON.readTree(requests.get(199).line());
        assertEquals(100, failed.get("seq").asLong());
        assertEquals((RUN_START + 4_000) * NANOS_PER_MILLI, requests.get(199).timestampNanos());
        assertFalse(failed.get("ok").asBoolean());
        assertTrue(failed.get("pod").isNull());
        assertEquals(0, failed.get("status").asInt());
        assertEquals("HttpTimeoutException: request timed out", failed.get("error").asText());
    }

    @Test
    void every_step_and_every_trigger_is_an_entry_at_its_start_time() throws Exception {
        RunRecord record = TestRuns.run("run-s3", "way-c", "s3-infra-outage", 1, 1, 1, List.of(
                        step(0, 0, Scenario.Action.INFRA_DOWN, 90_000, 90_500),
                        commit(1, 30, new Payload("v2", "1000"), 120_000, 121_900, 0,
                                new ChangeTrigger.Result("busrefresh", 121_900, 136_940, 124, "timed out")),
                        step(2, 60, Scenario.Action.DELETE_POD, 150_000, 150_100),
                        step(3, 120, Scenario.Action.INFRA_UP, 210_000, 210_110)),
                cleanS1(), samples(0, 360_000), 360_000);

        List<LokiExport.Entry> steps = LokiExport.streams(record).get(1).entries();

        assertEquals(List.of("infraDown", "commit", "trigger", "deletePod", "infraUp"),
                steps.stream().map(entry -> json(entry.line()).get("step").asText()).toList());
        assertEquals(List.of(90_000L, 120_000L, 121_900L, 150_000L, 210_000L),
                steps.stream().map(entry -> entry.timestampNanos() / NANOS_PER_MILLI - RUN_START).toList());
        assertEquals("{\"step\":\"commit\",\"index\":1,\"exitCode\":0,\"durationMs\":1900.0,\"atMs\":120000.0,"
                + "\"configVersion\":\"v2\"}", steps.get(1).line());
        assertEquals("{\"step\":\"trigger\",\"index\":1,\"exitCode\":124,\"durationMs\":15040.0,\"atMs\":121900.0}",
                steps.get(2).line());
    }

    @Test
    void the_run_entry_names_the_run_and_its_time_range() {
        LokiExport.Entry run = LokiExport.streams(TestRuns.s1(cleanS1())).get(2).entries().get(0);

        assertEquals(RUN_START * NANOS_PER_MILLI, run.timestampNanos());
        assertEquals("{\"runId\":\"run-1\",\"campaign\":\"20261115-200000_test\",\"scenario\":\"s1-valid-change\","
                + "\"approach\":\"way-a\",\"block\":1,\"attempt\":1,\"requests\":12000,"
                + "\"fromEpochMillis\":1794769200000,\"toEpochMillis\":1794769440000}", run.line());
    }

    @Test
    void the_push_body_has_one_stream_with_its_labels_and_timestamps_as_text() {
        String body = LokiExport.pushBody(Map.of("job", "runner"),
                List.of(new LokiExport.Entry(1794769200002000000L, "{\"seq\":0}"),
                        new LokiExport.Entry(1794769200022000000L, "{\"seq\":1}")));

        assertEquals("{\"streams\":[{\"stream\":{\"job\":\"runner\"},\"values\":["
                + "[\"1794769200002000000\",\"{\\\"seq\\\":0}\"],[\"1794769200022000000\",\"{\\\"seq\\\":1}\"]]}]}", body);
    }

    @Test
    void load_pushes_the_requests_in_batches_of_2000_lines_then_the_steps_and_the_run() throws Exception {
        LokiExport.Loaded loaded = new LokiExport(loki, Duration.ZERO).load(TestRuns.s1(cleanS1()));

        assertEquals(new LokiExport.Loaded("run-1", RUN_START, RUN_START + 243_000, 12_000, 1, false), loaded);
        assertEquals(6 + 1 + 1, pushes.size());
        for (int batch = 0; batch < 6; batch++) {
            JsonNode stream = json(pushes.get(batch)).get("streams").get(0);
            assertEquals("request", stream.get("stream").get("kind").asText());
            assertEquals(2_000, stream.get("values").size());
        }
        assertEquals("step", json(pushes.get(6)).get("streams").get(0).get("stream").get("kind").asText());
        assertEquals("run", json(pushes.get(7)).get("streams").get(0).get("stream").get("kind").asText());
    }

    @Test
    void load_asks_loki_for_the_run_first_and_pushes_nothing_when_it_is_there() throws Exception {
        queryAnswer = "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":["
                + "{\"stream\":{\"run_id\":\"run-1\"},\"values\":[[\"1794769200002000000\",\"{}\"]]}]}}";

        LokiExport.Loaded loaded = new LokiExport(loki, Duration.ZERO).load(TestRuns.s1(cleanS1()));

        assertTrue(loaded.skipped());
        assertEquals(12_000, loaded.requests());
        assertEquals(List.of(), pushes);
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).contains("query={job=\"runner\",run_id=\"run-1\"}"), queries.get(0));
        assertTrue(queries.get(0).contains("start=" + (RUN_START - 60_000) * NANOS_PER_MILLI), queries.get(0));
        assertTrue(queries.get(0).contains("end=" + (RUN_START + 243_000 + 60_000) * NANOS_PER_MILLI), queries.get(0));
    }

    @Test
    void a_push_refused_with_429_is_sent_again() throws Exception {
        pushStatuses.addAll(List.of(429, 429));

        LokiExport.Loaded loaded = new LokiExport(loki, Duration.ZERO).load(TestRuns.s1(cleanS1()));

        assertFalse(loaded.skipped());
        assertEquals(2 + 6 + 1 + 1, pushes.size());
        assertEquals(pushes.get(0), pushes.get(1));
        assertEquals(pushes.get(0), pushes.get(2));
    }

    @Test
    void a_push_refused_with_another_status_stops_the_load_with_the_answer_of_loki() {
        pushStatuses.add(400);

        IOException failure = assertThrows(IOException.class,
                () -> new LokiExport(loki, Duration.ZERO).load(TestRuns.s1(cleanS1())));

        assertEquals("Loki push failed with status 400: refused in the test", failure.getMessage());
        assertEquals(1, pushes.size());
    }

    @Test
    void a_push_refused_with_429_six_times_stops_the_load() {
        pushStatuses.addAll(List.of(429, 429, 429, 429, 429, 429));

        IOException failure = assertThrows(IOException.class,
                () -> new LokiExport(loki, Duration.ZERO).load(TestRuns.s1(cleanS1())));

        assertTrue(failure.getMessage().startsWith("Loki push failed with status 429"), failure.getMessage());
        assertEquals(6, pushes.size());
    }

    @Test
    void the_dashboard_url_names_the_run_and_its_time_range() {
        assertEquals("http://localhost:3000/d/run-results/run-results?var-run=run-1&from=1794769200000&to=1794769443000",
                LokiExport.dashboardUrl("http://localhost:3000",
                        new LokiExport.Loaded("run-1", RUN_START, RUN_START + 243_000, 12_000, 1, false)));
    }

    private static JsonNode json(String text) {
        try {
            return Fixtures.JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
