package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static at.technikum.thesis.runner.Scenario.Action.COMMIT;
import static at.technikum.thesis.runner.Scenario.Action.DELETE_POD;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_DOWN;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioTest {

    private static final Path SCENARIOS = Fixtures.ROOT.resolve("scenarios");
    private static final Payload V2 = new Payload("v2", "1000");

    @Test
    void scenarioOneIsOneValidCommitWithTheGateOff() throws Exception {
        Scenario scenario = Scenario.read(SCENARIOS.resolve("s1-valid-change.json"));

        assertEquals("s1-valid-change", scenario.name());
        assertFalse(scenario.gate());
        assertEquals(List.of(), scenario.invalidVersions());
        assertEquals(List.of(new Scenario.Step(0, COMMIT, new Payload("v2", "1000"))), scenario.steps());
        assertEquals(List.of(), scenario.problems(Fixtures.protocol()));
        assertEquals(0, scenario.lastStepAtSeconds());
        assertEquals(Set.of("v1", "v2"), scenario.declaredVersions(Fixtures.protocol()));
    }

    @Test
    void scenarioTwoCommitsAnInvalidValueAndFixesItAfterTwoMinutesWithTheGateOn() throws Exception {
        Scenario scenario = Scenario.read(SCENARIOS.resolve("s2-invalid-config.json"));

        assertEquals("s2-invalid-config", scenario.name());
        assertTrue(scenario.gate());
        assertEquals(List.of("v2"), scenario.invalidVersions());
        assertEquals(List.of(
                new Scenario.Step(0, COMMIT, new Payload("v2", "abc")),
                new Scenario.Step(120, COMMIT, new Payload("v3", "1000"))), scenario.steps());
        assertEquals(List.of(), scenario.problems(Fixtures.protocol()));
        assertEquals(120, scenario.lastStepAtSeconds());
        assertEquals(List.of(new Payload("v2", "abc"), new Payload("v3", "1000")), scenario.payloads());
        assertEquals(List.of("v1", "v2", "v3"), List.copyOf(scenario.declaredVersions(Fixtures.protocol())));
    }

    @Test
    void scenarioThreeTakesTheInfrastructureDownCommitsDeletesAPodAndRestores() throws Exception {
        Scenario scenario = Scenario.read(SCENARIOS.resolve("s3-infra-outage.json"));

        assertEquals("s3-infra-outage", scenario.name());
        assertFalse(scenario.gate());
        assertEquals(List.of(), scenario.invalidVersions());
        assertEquals(List.of(
                new Scenario.Step(0, INFRA_DOWN, null),
                new Scenario.Step(30, COMMIT, new Payload("v2", "1000")),
                new Scenario.Step(60, DELETE_POD, null),
                new Scenario.Step(120, INFRA_UP, null)), scenario.steps());
        assertEquals(List.of(), scenario.problems(Fixtures.protocol()));
        assertEquals(120, scenario.lastStepAtSeconds());
    }

    @Test
    void theStructureOfAScenarioDecidesWhichMetricsAreReported() throws Exception {
        Set<MetricId> criteria = EnumSet.of(MetricId.P1, MetricId.P2, MetricId.P3, MetricId.C1, MetricId.C2,
                MetricId.C3, MetricId.A1, MetricId.A2, MetricId.A3, MetricId.F1, MetricId.F2, MetricId.F3,
                MetricId.O1, MetricId.O2, MetricId.O3, MetricId.O4, MetricId.O5);

        assertEquals(criteria, Scenario.read(SCENARIOS.resolve("s1-valid-change.json")).reportedMetrics());
        assertEquals(EnumSet.of(MetricId.E1, MetricId.E2, MetricId.E3, MetricId.E4, MetricId.R1),
                Scenario.read(SCENARIOS.resolve("s2-invalid-config.json")).reportedMetrics());
        assertEquals(EnumSet.of(MetricId.U1, MetricId.U2, MetricId.U3, MetricId.U4, MetricId.U5),
                Scenario.read(SCENARIOS.resolve("s3-infra-outage.json")).reportedMetrics());
    }

    @Test
    void rejectsAScenarioWithoutSteps() throws Exception {
        Scenario scenario = new Scenario("empty", "Empty", false, null, null);

        assertEquals(List.of("steps is empty"), scenario.problems(Fixtures.protocol()));
    }

    @Test
    void rejectsNegativeAndDecreasingOffsets() throws Exception {
        Scenario negative = scenario(List.of(), new Scenario.Step(-1, COMMIT, V2));
        Scenario decreasing = scenario(List.of(),
                new Scenario.Step(30, COMMIT, V2), new Scenario.Step(10, DELETE_POD, null));

        assertEquals(List.of("step 0: atSeconds must not be negative"), negative.problems(Fixtures.protocol()));
        assertEquals(List.of("step 1: atSeconds must not decrease"), decreasing.problems(Fixtures.protocol()));
    }

    @Test
    void equalOffsetsAreAllowed() throws Exception {
        Scenario scenario = scenario(List.of(),
                new Scenario.Step(0, COMMIT, V2), new Scenario.Step(0, DELETE_POD, null));

        assertEquals(List.of(), scenario.problems(Fixtures.protocol()));
    }

    @Test
    void commitNeedsAPayloadAndOtherActionsTakeNone() throws Exception {
        Scenario scenario = scenario(List.of(),
                new Scenario.Step(0, COMMIT, null),
                new Scenario.Step(10, COMMIT, new Payload("v2", null)),
                new Scenario.Step(20, DELETE_POD, V2));

        assertEquals(List.of(
                "step 0: commit needs a payload with configVersion and timeoutMs",
                "step 1: commit needs a payload with configVersion and timeoutMs",
                "step 2: only commit takes a payload"), scenario.problems(Fixtures.protocol()));
    }

    @Test
    void everyPayloadVersionIsUniqueAndDiffersFromTheInitialVersion() throws Exception {
        Scenario repeated = scenario(List.of(),
                new Scenario.Step(0, COMMIT, V2), new Scenario.Step(10, COMMIT, new Payload("v2", "2000")));
        Scenario initial = scenario(List.of(), new Scenario.Step(0, COMMIT, new Payload("v1", "1000")));

        assertEquals(List.of("step 1: version v2 is used by more than one payload"),
                repeated.problems(Fixtures.protocol()));
        assertEquals(List.of("step 0: version must differ from the initial version v1"),
                initial.problems(Fixtures.protocol()));
    }

    @Test
    void invalidVersionsMustBePayloadVersions() throws Exception {
        Scenario scenario = scenario(List.of("v9"), new Scenario.Step(0, COMMIT, V2));

        assertEquals(List.of("invalid version v9 is not the version of any payload"),
                scenario.problems(Fixtures.protocol()));
    }

    @Test
    void infraUpNeedsAnEarlierInfraDownAndEveryInfraDownIsRestored() throws Exception {
        Scenario upOnly = scenario(List.of(), new Scenario.Step(0, INFRA_UP, null));
        Scenario downOnly = scenario(List.of(),
                new Scenario.Step(0, INFRA_DOWN, null), new Scenario.Step(30, COMMIT, V2));
        Scenario downAgain = scenario(List.of(),
                new Scenario.Step(0, INFRA_DOWN, null), new Scenario.Step(10, INFRA_UP, null),
                new Scenario.Step(20, INFRA_DOWN, null));

        assertEquals(List.of("step 0: infraUp needs an earlier infraDown"), upOnly.problems(Fixtures.protocol()));
        assertEquals(List.of("infraDown is not followed by an infraUp"), downOnly.problems(Fixtures.protocol()));
        assertEquals(List.of("infraDown is not followed by an infraUp"), downAgain.problems(Fixtures.protocol()));
    }

    @Test
    void readFailsOnAnUnknownFieldAndAnUnknownAction(@TempDir Path dir) throws Exception {
        Path unknownField = dir.resolve("field.json");
        Files.writeString(unknownField, "{\"name\":\"x\",\"gate\":false,\"observationSeconds\":30,"
                + "\"steps\":[{\"atSeconds\":0,\"action\":\"deletePod\"}]}");
        Path unknownAction = dir.resolve("action.json");
        Files.writeString(unknownAction, "{\"name\":\"x\",\"gate\":false,"
                + "\"steps\":[{\"atSeconds\":0,\"action\":\"restartNode\"}]}");

        assertThrows(Exception.class, () -> Scenario.read(unknownField));
        assertThrows(Exception.class, () -> Scenario.read(unknownAction));
    }

    private static Scenario scenario(List<String> invalidVersions, Scenario.Step... steps) {
        return new Scenario("test", "Test", false, invalidVersions, List.of(steps));
    }
}
