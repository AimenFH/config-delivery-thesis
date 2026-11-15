package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GateOffReadinessTest {

    private static void assertReadinessAsIn010(RunningProbe probe) {
        RunningProbe.Answer readiness = probe.get("/actuator/health/readiness");
        assertEquals(200, readiness.status());
        assertEquals("{\"status\":\"UP\"}", readiness.body());
        assertFalse(probe.get("/actuator/health").body().contains("configGate"));
    }

    private static void assertLivenessUp(RunningProbe probe) {
        RunningProbe.Answer liveness = probe.get("/actuator/health/liveness");
        assertEquals(200, liveness.status());
        assertEquals("{\"status\":\"UP\"}", liveness.body());
    }

    private static void assertValidEndpoints(RunningProbe probe) {
        RunningProbe.Answer configHealth = probe.get("/config-health");
        assertEquals(200, configHealth.status());
        assertEquals("VALID", configHealth.json().get("status").asText());
        assertEquals("v0", configHealth.json().get("configVersion").asText());
        assertEquals(2, configHealth.json().size());
        assertProbeAnswers(probe);
    }

    private static void assertInvalidEndpoints(RunningProbe probe) {
        RunningProbe.Answer configHealth = probe.get("/config-health");
        assertEquals(503, configHealth.status());
        assertEquals("INVALID", configHealth.json().get("status").asText());
        assertEquals(1, configHealth.json().get("violations").size());
        assertEquals("demo.timeoutMs='abc' is not an integer",
                configHealth.json().get("violations").get(0).asText());
        assertEquals(2, configHealth.json().size());
        assertProbeAnswers(probe);
    }

    private static void assertProbeAnswers(RunningProbe probe) {
        RunningProbe.Answer answer = probe.get("/probe");
        assertEquals(200, answer.status());
        assertEquals("v0", answer.json().get("configVersion").asText());
        assertEquals("UNSET", answer.json().get("value").asText());
        assertEquals(5, answer.json().size());
    }

    @Test
    void gate_variable_unset_valid_configuration_readiness_is_200_with_the_0_1_0_body() {
        try (RunningProbe probe = RunningProbe.start()) {
            assertReadinessAsIn010(probe);
            assertLivenessUp(probe);
            assertValidEndpoints(probe);
        }
    }

    @Test
    void gate_variable_unset_timeoutMs_abc_readiness_is_still_200_with_the_0_1_0_body() {
        try (RunningProbe probe = RunningProbe.start("demo.timeoutMs=abc")) {
            assertReadinessAsIn010(probe);
            assertLivenessUp(probe);
            assertInvalidEndpoints(probe);
        }
    }

    @Test
    void gate_variable_false_valid_configuration_readiness_is_200_with_the_0_1_0_body() {
        try (RunningProbe probe = RunningProbe.start("CONFIG_GATE_ENABLED=false")) {
            assertReadinessAsIn010(probe);
            assertLivenessUp(probe);
            assertValidEndpoints(probe);
        }
    }

    @Test
    void gate_variable_false_timeoutMs_abc_readiness_is_still_200_with_the_0_1_0_body() {
        try (RunningProbe probe = RunningProbe.start("CONFIG_GATE_ENABLED=false", "demo.timeoutMs=abc")) {
            assertReadinessAsIn010(probe);
            assertLivenessUp(probe);
            assertInvalidEndpoints(probe);
        }
    }
}
