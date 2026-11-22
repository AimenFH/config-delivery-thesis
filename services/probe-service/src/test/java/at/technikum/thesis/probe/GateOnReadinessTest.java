package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GateOnReadinessTest {

    private static RunningProbe gateOn(String... properties) {
        String[] all = new String[properties.length + 1];
        all[0] = "CONFIG_GATE_ENABLED=true";
        System.arraycopy(properties, 0, all, 1, properties.length);
        return RunningProbe.start(all);
    }

    private static void assertLivenessUpWithoutGate(RunningProbe probe) {
        RunningProbe.Answer liveness = probe.get("/actuator/health/liveness");
        assertEquals(200, liveness.status());
        assertEquals("{\"status\":\"UP\"}", liveness.body());
        assertEquals(404, probe.get("/actuator/health/liveness/configGate").status());
    }

    private static void assertProbeAnswers(RunningProbe probe, String version) {
        RunningProbe.Answer answer = probe.get("/probe");
        assertEquals(200, answer.status());
        assertEquals(version, answer.json().get("configVersion").asString());
        assertEquals(5, answer.json().size());
    }

    private static void assertReady(RunningProbe probe, String version) {
        assertEquals(200, probe.get("/actuator/health/readiness").status());
        assertLivenessUpWithoutGate(probe);
        RunningProbe.Answer configHealth = probe.get("/config-health");
        assertEquals(200, configHealth.status());
        assertEquals("VALID", configHealth.json().get("status").asString());
        assertEquals(version, configHealth.json().get("configVersion").asString());
        assertEquals("UP", probe.get("/actuator/health").json()
                .get("components").get("configGate").get("status").asString());
        assertProbeAnswers(probe, version);
    }

    private static void assertNotReady(RunningProbe probe, String version, String violation) {
        assertEquals(503, probe.get("/actuator/health/readiness").status());
        assertLivenessUpWithoutGate(probe);
        RunningProbe.Answer configHealth = probe.get("/config-health");
        assertEquals(503, configHealth.status());
        assertEquals("INVALID", configHealth.json().get("status").asString());
        assertEquals(1, configHealth.json().get("violations").size());
        assertEquals(violation, configHealth.json().get("violations").get(0).asString());
        var gate = probe.get("/actuator/health").json().get("components").get("configGate");
        assertEquals("DOWN", gate.get("status").asString());
        assertEquals(1, gate.get("details").get("violations").size());
        assertEquals(violation, gate.get("details").get("violations").get(0).asString());
        assertProbeAnswers(probe, version);
    }

    @Test
    void gate_on_valid_configuration_readiness_is_200() {
        try (RunningProbe probe = gateOn()) {
            assertReady(probe, "v0");
        }
    }

    @Test
    void gate_on_timeoutMs_abc_readiness_is_503_and_liveness_stays_200() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=abc")) {
            assertNotReady(probe, "v0", "demo.timeoutMs='abc' is not an integer");
        }
    }

    @Test
    void gate_on_timeoutMs_99_is_below_100_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=99")) {
            assertNotReady(probe, "v0", "demo.timeoutMs=99 outside [100,30000]");
        }
    }

    @Test
    void gate_on_timeoutMs_30001_is_above_30000_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=30001")) {
            assertNotReady(probe, "v0", "demo.timeoutMs=30001 outside [100,30000]");
        }
    }

    @Test
    void gate_on_timeoutMs_100_is_the_lower_limit_readiness_is_200() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=100")) {
            assertReady(probe, "v0");
        }
    }

    @Test
    void gate_on_timeoutMs_30000_is_the_upper_limit_readiness_is_200() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=30000")) {
            assertReady(probe, "v0");
        }
    }

    @Test
    void gate_on_configVersion_2_does_not_match_v_number_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.configVersion=2")) {
            assertNotReady(probe, "2", "demo.configVersion='2' does not match v<number>");
        }
    }

    @Test
    void gate_on_configVersion_v_without_a_number_does_not_match_v_number_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.configVersion=v")) {
            assertNotReady(probe, "v", "demo.configVersion='v' does not match v<number>");
        }
    }

    @Test
    void gate_on_configVersion_v2x_with_text_after_the_number_does_not_match_v_number_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.configVersion=v2x")) {
            assertNotReady(probe, "v2x", "demo.configVersion='v2x' does not match v<number>");
        }
    }

    @Test
    void gate_on_configVersion_V2_with_a_capital_letter_does_not_match_v_number_readiness_is_503() {
        try (RunningProbe probe = gateOn("demo.configVersion=V2")) {
            assertNotReady(probe, "V2", "demo.configVersion='V2' does not match v<number>");
        }
    }

    @Test
    void gate_on_configVersion_v10_with_two_digits_matches_v_number_readiness_is_200() {
        try (RunningProbe probe = gateOn("demo.configVersion=v10")) {
            assertReady(probe, "v10");
        }
    }

    @Test
    void gate_on_readiness_body_shows_no_more_than_the_status() {
        try (RunningProbe probe = gateOn("demo.timeoutMs=abc")) {
            assertEquals("{\"status\":\"DOWN\"}", probe.get("/actuator/health/readiness").body());
            assertFalse(probe.get("/actuator/health/liveness").body().contains("configGate"));
        }
    }
}
