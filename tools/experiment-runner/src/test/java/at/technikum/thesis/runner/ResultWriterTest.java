package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static at.technikum.thesis.runner.TestRuns.commit;
import static at.technikum.thesis.runner.TestRuns.failure;
import static at.technikum.thesis.runner.TestRuns.load;
import static at.technikum.thesis.runner.TestRuns.replace;
import static at.technikum.thesis.runner.TestRuns.samples;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultWriterTest {

    private static final RunMetrics VERDICT = new RunMetrics("20261115-200000_way-c_b01", "way-c", "s2-invalid-config",
            1, RunMetrics.Verdict.INVALID, List.of("baseline: 1 failed requests", "no resource samples"),
            EnumSet.of(RunMetrics.Flag.LOW_RESOLUTION, RunMetrics.Flag.NOT_CONVERGED), true, List.of());

    @Test
    void a_run_record_survives_save_and_the_reader(@TempDir Path dir) throws Exception {
        RunRecord record = record("20261115-200000_way-c_b01");
        ResultWriter store = new ResultWriter(dir);

        store.save(record, VERDICT);

        assertEquals(record, store.read("20261115-200000_way-c_b01"));
    }

    @Test
    void save_writes_the_four_raw_files_of_the_plan(@TempDir Path dir) throws Exception {
        new ResultWriter(dir).save(record("run-7"), VERDICT);

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("run-7.cluster.json", "run-7.meta.json", "run-7.ndjson", "run-7.resources.ndjson"),
                    files.map(file -> file.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void the_observation_file_has_one_request_per_line_and_the_resource_file_one_container_sample_per_line(
            @TempDir Path dir) throws Exception {
        RunRecord record = record("run-7");

        new ResultWriter(dir).save(record, VERDICT);

        List<String> observations = Files.readAllLines(dir.resolve("run-7.ndjson"));
        List<String> resources = Files.readAllLines(dir.resolve("run-7.resources.ndjson"));
        assertEquals(300, observations.size());
        assertEquals(record.observations().get(0), Fixtures.JSON.readValue(observations.get(0), Observation.class));
        assertTrue(observations.get(0).contains("\"intendedAt\":0.0"), observations.get(0));
        assertEquals(3 * 4, resources.size());
        assertEquals(record.resources().get(5), Fixtures.JSON.readValue(resources.get(5), ResourceSample.class));
        assertFalse(new String(Files.readAllBytes(dir.resolve("run-7.ndjson")), StandardCharsets.UTF_8).contains("\r"));
    }

    @Test
    void the_cluster_file_holds_the_pod_lists_and_the_events(@TempDir Path dir) throws Exception {
        RunRecord record = record("run-7");

        new ResultWriter(dir).save(record, VERDICT);

        assertEquals(record.cluster(), Fixtures.JSON.readValue(dir.resolve("run-7.cluster.json").toFile(),
                RunRecord.ClusterState.class));
    }

    @Test
    void the_meta_file_holds_every_other_field_plus_t_cs_T0_verdict_reasons_and_flags(@TempDir Path dir)
            throws Exception {
        new ResultWriter(dir).save(record("run-7"), VERDICT);

        JsonNode meta = Fixtures.JSON.readTree(dir.resolve("run-7.meta.json").toFile());

        assertEquals(17 + 5, meta.size());
        assertFalse(meta.has("observations"));
        assertFalse(meta.has("resources"));
        assertFalse(meta.has("cluster"));
        assertEquals("way-c", meta.path("approach").asText());
        assertEquals(2, meta.path("block").asInt());
        assertEquals(3, meta.path("positionInBlock").asInt());
        assertEquals(1, meta.path("protocolVersion").asInt());
        assertEquals(90_000, meta.path("tCs").asDouble());
        assertEquals(90_120, meta.path("t0").asDouble());
        assertEquals(0, meta.path("steps").get(0).path("exitCode").asInt());
        assertEquals(90_160, meta.path("steps").get(0).path("trigger").path("finishedAt").asDouble());
        assertEquals("git ls-remote", meta.path("sourceCheck").path("command").asText());
        assertEquals("INVALID", meta.path("verdict").asText());
        assertEquals("[\"baseline: 1 failed requests\",\"no resource samples\"]",
                meta.path("invalidReasons").toString());
        assertEquals("[\"NOT_CONVERGED\",\"LOW_RESOLUTION\"]", meta.path("flags").toString());
    }

    @Test
    void a_run_with_a_failed_commit_and_empty_lists_survives_save_and_the_reader(@TempDir Path dir)
            throws Exception {
        RunRecord record = TestRuns.run("run-8", "way-a", "s1-valid-change", 1, 1, 2,
                List.of(commit(0, 0, new Payload("v2", "1000"), 90_000, 90_100, 1, null)),
                List.of(), List.of(), 240_000);
        ResultWriter store = new ResultWriter(dir);

        store.save(record, VERDICT);

        assertEquals(record, store.read("run-8"));
        assertTrue(Fixtures.JSON.readTree(dir.resolve("run-8.meta.json").toFile()).path("t0").isNull());
    }

    @Test
    void the_runs_of_a_folder_are_listed_sorted_by_run_id(@TempDir Path dir) throws Exception {
        ResultWriter store = new ResultWriter(dir);
        for (String runId : List.of("20261115-213000_way-b_b02", "20261115-200000_way-c_b01",
                "20261115-204500_way-a_b01")) {
            store.save(record(runId), VERDICT);
        }
        Files.writeString(dir.resolve("campaign.json"), "{}");
        Files.writeString(dir.resolve("fingerprint.json"), "{}");
        Files.createDirectories(dir.resolve("approaches"));

        assertEquals(List.of("20261115-200000_way-c_b01", "20261115-204500_way-a_b01", "20261115-213000_way-b_b02"),
                store.runIds());
    }

    private static RunRecord record(String runId) throws Exception {
        List<Observation> observations = replace(load(0, 300, (pod, at) -> at < 3_000 ? "v1" : "v2"), failure(150));
        RunRecord.PodInfo pod = new RunRecord.PodInfo("thesis", "probe-service-a", "thesis-worker",
                "sha256:1234", "2026-11-15T20:00:00Z");
        RunRecord.ClusterState cluster = new RunRecord.ClusterState(List.of(pod), List.of(pod, pod),
                List.of(new RunRecord.InfraState("platform", "config-server", 1, "100m", null)),
                Fixtures.JSON.readTree("{\"kind\":\"List\",\"items\":[{\"reason\":\"Killing\",\"count\":2}]}"));
        return new RunRecord(runId, "20261115-195500_s2", "way-c", "s2-invalid-config", 1, 2, 3, 1,
                "2026-11-15T19:00:00Z", 1794769200000L, true, 0.25, 6_000.5, 300,
                new ChangeTrigger.Result("git ls-remote", -700, -350.125, 0, "abc HEAD"),
                List.of(commit(0, 0, new Payload("v2", "abc"), 90_000, 90_120, 0,
                        new ChangeTrigger.Result("busrefresh", 90_120, 90_160, 0, ""))),
                observations, samples(0, 10_000), cluster, List.of("pod list after the run failed"));
    }
}
