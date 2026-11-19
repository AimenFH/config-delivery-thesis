package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void protocolVersionOneHoldsTheConstantsOfThePlan() throws Exception {
        Protocol protocol = Fixtures.protocol();

        assertEquals(1, protocol.protocolVersion());
        assertEquals("thesis", protocol.namespace());
        assertEquals("probe-service", protocol.deployment());
        assertEquals("probe-service", protocol.service());
        assertEquals(URI.create("http://localhost:30080/probe"), protocol.uri());
        assertEquals(3, protocol.replicas());
        assertEquals(50, protocol.ratePerSecond());
        assertEquals(2000, protocol.requestTimeoutMillis());
        assertEquals("v1", protocol.initialVersion());
        assertEquals("1000", protocol.initialTimeoutMs());
        assertEquals("v2", protocol.targetVersion());
        assertEquals("v0", protocol.unconfiguredVersion());
        assertEquals(100, protocol.settleSeconds());
        assertEquals(30, protocol.warmupSeconds());
        assertEquals(60, protocol.baselineSeconds());
        assertEquals(150, protocol.observationSeconds());
        assertEquals(5, protocol.resourceSamplingSeconds());
        assertEquals(1000, protocol.coverageLimitMillis());
        assertEquals(25, protocol.maxBlocks());
        assertEquals(2, protocol.retriesPerBlock());
    }

    @Test
    void steadyStateInstrumentLimitsAndStatisticsFollowThePlan() throws Exception {
        Protocol protocol = Fixtures.protocol();

        assertEquals(60, protocol.steadyProbes());
        assertEquals(200, protocol.steadyMaxLatencyMillis());
        assertEquals(10, protocol.steadySnapshotGapSeconds());
        assertEquals(120, protocol.steadyTimeoutSeconds());
        assertEquals(15, protocol.triggerTimeoutSeconds());
        assertEquals(100, protocol.scheduleLagLimitMillis());
        assertEquals(0.01, protocol.unsentLimitRatio());
        assertEquals(15, protocol.resourceGapLimitSeconds());
        assertEquals(10000, protocol.bootstrapResamples());
        assertEquals(20261115L, protocol.bootstrapSeed());
        assertEquals(List.of("approaches/way-a/approach.json", "approaches/way-b/approach.json",
                "approaches/way-c/approach.json"), protocol.approaches());
    }

    @Test
    void workloadHoldsTheFixedFieldsOfEveryApproach() throws Exception {
        Protocol.Workload workload = Fixtures.protocol().workload();

        assertEquals(new Protocol.Workload("probe-service:0.2.0", "probe", "250m", "384Mi", "500m", "512Mi",
                "/actuator/health/readiness", 8080, 2, "kubernetes.io/hostname", 1, "DoNotSchedule",
                "NodePort", 80, 8080, 30080, Map.of("app", "probe-service")), workload);
    }

    @Test
    void theFileHasNoProblems() throws Exception {
        assertEquals(List.of(), Fixtures.protocol().problems());
    }

    @Test
    void rejectsABaselineOrObservationOfZero() throws Exception {
        Protocol noBaseline = Fixtures.protocolWith(tree -> tree.put("baselineSeconds", 0));
        Protocol noObservation = Fixtures.protocolWith(tree -> tree.put("observationSeconds", 0));

        assertEquals(List.of("baselineSeconds must be positive"), noBaseline.problems());
        assertEquals(List.of("observationSeconds must be positive"), noObservation.problems());
    }

    @Test
    void rejectsMissingAndNonPositiveValues() throws Exception {
        Protocol protocol = Fixtures.protocolWith(tree -> {
            tree.remove("namespace");
            tree.remove("replicas");
            tree.put("ratePerSecond", -50);
            tree.put("unsentLimitRatio", 0);
            ((ObjectNode) tree.get("workload")).remove("image");
            ((ObjectNode) tree.get("workload")).put("servicePort", 0);
        });

        assertEquals(List.of("namespace is missing", "replicas must be positive", "ratePerSecond must be positive",
                "unsentLimitRatio must be positive", "workload.image is missing",
                "workload.servicePort must be positive"), protocol.problems());
    }

    @Test
    void rejectsAnEmptyApproachListAndAMissingWorkload() throws Exception {
        Protocol protocol = Fixtures.protocolWith(tree -> {
            tree.putArray("approaches");
            tree.remove("workload");
        });

        assertEquals(List.of("approaches is empty", "workload is missing"), protocol.problems());
    }

    @Test
    void rejectsAChangeThatChangesNothing() throws Exception {
        Protocol protocol = Fixtures.protocolWith(tree -> tree.put("targetVersion", "v1"));

        assertEquals(List.of("initialVersion and targetVersion must differ"), protocol.problems());
    }

    @Test
    void rejectsAMissingUnconfiguredVersionAndATriggerTimeoutOfZero() throws Exception {
        Protocol protocol = Fixtures.protocolWith(tree -> {
            tree.remove("unconfiguredVersion");
            tree.put("triggerTimeoutSeconds", 0);
        });

        assertEquals(List.of("unconfiguredVersion is missing", "triggerTimeoutSeconds must be positive"),
                protocol.problems());
    }

    @Test
    void rejectsAnUnconfiguredVersionThatAPayloadOfTheProtocolAlsoUses() throws Exception {
        Protocol asInitial = Fixtures.protocolWith(tree -> tree.put("unconfiguredVersion", "v1"));
        Protocol asTarget = Fixtures.protocolWith(tree -> tree.put("unconfiguredVersion", "v2"));

        assertEquals(List.of("unconfiguredVersion must differ from initialVersion and targetVersion"),
                asInitial.problems());
        assertEquals(List.of("unconfiguredVersion must differ from initialVersion and targetVersion"),
                asTarget.problems());
    }

    @Test
    void readFailsOnAnInvalidFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("protocol.json");
        Fixtures.JSON.writeValue(file.toFile(), Fixtures.protocolWith(tree -> tree.put("observationSeconds", 0)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Protocol.read(file));
        assertTrue(failure.getMessage().contains("observationSeconds must be positive"), failure.getMessage());
    }

    @Test
    void readFailsOnAnUnknownField(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("protocol.json");
        ObjectNode tree = (ObjectNode) Fixtures.JSON.readTree(Fixtures.PROTOCOL.toFile());
        tree.put("pauseSeconds", 30);
        Files.writeString(file, tree.toString());

        assertThrows(Exception.class, () -> Protocol.read(file));
    }
}
