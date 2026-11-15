package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static at.technikum.thesis.runner.Scenario.Action.COMMIT;
import static at.technikum.thesis.runner.Scenario.Action.DELETE_POD;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_DOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunRecordTest {

    @Test
    void aRunRecordSurvivesWritingAndReading() throws Exception {
        RunRecord record = record(List.of(
                step(0, 0, COMMIT, new Payload("v2", "abc"), 90_000, 90_120, 0),
                step(1, 120, COMMIT, new Payload("v3", "1000"), 210_000, 210_090, 0)));

        String json = Fixtures.JSON.writeValueAsString(record);

        assertEquals(record, Fixtures.JSON.readValue(json, RunRecord.class));
    }

    @Test
    void theConvenienceMethodsAreNotWritten() throws Exception {
        JsonNode tree = Fixtures.JSON.valueToTree(record(List.of(
                step(0, 0, COMMIT, new Payload("v2", "1000"), 90_000, 90_120, 0))));

        assertEquals(20, tree.size());
        assertFalse(tree.has("reference"));
        assertFalse(tree.has("commits"));
        assertFalse(tree.has("tCs"));
        assertFalse(tree.has("t0"));
        assertFalse(tree.path("steps").get(0).has("t0"));
        assertEquals("commit", tree.path("steps").get(0).path("action").asText());
    }

    @Test
    void referenceIsTheStartOfTheFirstStepAndT0TheEndOfTheFirstCommit() {
        RunRecord record = record(List.of(
                step(0, 0, INFRA_DOWN, null, 90_000, 90_400, 0),
                step(1, 30, COMMIT, new Payload("v2", "1000"), 120_000, 120_150, 0),
                step(2, 60, DELETE_POD, null, 150_000, 150_300, 0)));

        assertEquals(90_000, record.reference());
        assertEquals(List.of(record.steps().get(1)), record.commits());
        assertEquals(120_000, record.tCs());
        assertEquals(120_150, record.t0());
    }

    @Test
    void aFailedCommitHasNoT0() {
        RunRecord record = record(List.of(
                step(0, 0, COMMIT, new Payload("v2", "abc"), 90_000, 90_120, 1),
                step(1, 120, COMMIT, new Payload("v3", "1000"), 210_000, 210_090, 0)));

        assertEquals(90_000, record.tCs());
        assertNull(record.t0());
        assertNull(record.steps().get(0).t0());
        assertEquals(210_090, record.steps().get(1).t0());
    }

    @Test
    void aRunWithoutStepsHasNoReference() {
        RunRecord record = record(List.of());

        assertNull(record.reference());
        assertNull(record.tCs());
        assertNull(record.t0());
    }

    @Test
    void runMetricsSurviveWritingAndReading() throws Exception {
        RunMetrics metrics = new RunMetrics("run-1", "way-b", "s1-valid-change", 3, RunMetrics.Verdict.VALID,
                List.of(), Set.of(RunMetrics.Flag.NOT_CONVERGED, RunMetrics.Flag.LOW_RESOLUTION), true,
                List.of(new Metric(MetricId.P1, "P1", 150_000.0, true, null),
                        new Metric(MetricId.F2, "F2_p99_H", 12.5, false, null),
                        new Metric(MetricId.U2, "U2_version", null, false, "v1")));

        String json = Fixtures.JSON.writeValueAsString(metrics);

        assertEquals(metrics, Fixtures.JSON.readValue(json, RunMetrics.class));
    }

    private static RunRecord.StepRecord step(int index, int atSeconds, Scenario.Action action, Payload payload,
                                             double startedAt, double finishedAt, int exitCode) {
        ChangeTrigger.Result trigger = action == COMMIT
                ? new ChangeTrigger.Result("trigger", finishedAt, finishedAt + 40, 0, "") : null;
        return new RunRecord.StepRecord(index, atSeconds, action, payload, startedAt, finishedAt, exitCode,
                "out", trigger, action == DELETE_POD ? "probe-service-abc" : null);
    }

    private static RunRecord record(List<RunRecord.StepRecord> steps) {
        RunRecord.PodInfo pod = new RunRecord.PodInfo("thesis", "probe-service-abc", "thesis-worker",
                "sha256:1234", "2026-11-15T20:00:00Z");
        RunRecord.ClusterState cluster = new RunRecord.ClusterState(List.of(pod), List.of(pod),
                List.of(new RunRecord.InfraState("platform", "config-server", 1, "100m", "256Mi")),
                Fixtures.JSON.createObjectNode().put("kind", "List"));
        return new RunRecord("20261115-200000_way-c_b01", "20261115-195500_s2", "way-c", "s2-invalid-config", 1,
                1, 2, 1, "2026-11-15T19:00:00Z", 1794769200000L, true, 12.5, 360_000, 18_000,
                new ChangeTrigger.Result("git ls-remote", -700, -350, 0, "abc HEAD"), steps,
                List.of(Fixtures.observation(0, 12.5, 14), Fixtures.observation(1, 32.5, 33)),
                List.of(new ResourceSample(5_000, "thesis-worker", "platform", "config-server-1", "config-server",
                        12.75, 268_435_456L, 1794769205000L)),
                cluster, List.of("pod list after the run failed"));
    }
}
