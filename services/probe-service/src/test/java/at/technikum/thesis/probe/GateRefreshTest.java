package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GateRefreshTest {

    private static void write(Path tree, String timeoutMs, String configVersion) throws IOException {
        Files.writeString(tree.resolve("demo.timeoutMs"), timeoutMs);
        Files.writeString(tree.resolve("demo.configVersion"), configVersion);
    }

    private static void assertAnswers(RunningProbe probe, int readiness, int configHealth, String version) {
        assertEquals(readiness, probe.get("/actuator/health/readiness").status());
        assertEquals(200, probe.get("/actuator/health/liveness").status());
        assertEquals("{\"status\":\"UP\"}", probe.get("/actuator/health/liveness").body());
        assertEquals(configHealth, probe.get("/config-health").status());
        RunningProbe.Answer answer = probe.get("/probe");
        assertEquals(200, answer.status());
        assertEquals(version, answer.json().get("configVersion").asString());
    }

    @Test
    void gate_on_readiness_judges_the_refreshed_values_after_post_actuator_refresh(@TempDir Path tree)
            throws IOException {
        write(tree, "1000", "v1");
        try (RunningProbe probe = RunningProbe.start(
                "CONFIG_GATE_ENABLED=true",
                "spring.config.import=configtree:" + tree.toAbsolutePath() + "/")) {
            assertAnswers(probe, 200, 200, "v1");

            write(tree, "abc", "v2");
            assertAnswers(probe, 200, 200, "v1");
            assertEquals(200, probe.post("/actuator/refresh").status());
            assertAnswers(probe, 503, 503, "v2");

            write(tree, "1000", "v3");
            assertEquals(200, probe.post("/actuator/refresh").status());
            assertAnswers(probe, 200, 200, "v3");
        }
    }

    @Test
    void gate_off_readiness_stays_200_after_a_refresh_to_an_invalid_value(@TempDir Path tree)
            throws IOException {
        write(tree, "1000", "v1");
        try (RunningProbe probe = RunningProbe.start(
                "spring.config.import=configtree:" + tree.toAbsolutePath() + "/")) {
            assertAnswers(probe, 200, 200, "v1");

            write(tree, "abc", "v2");
            assertEquals(200, probe.post("/actuator/refresh").status());
            assertAnswers(probe, 200, 503, "v2");
            assertEquals("{\"status\":\"UP\"}", probe.get("/actuator/health/readiness").body());
        }
    }
}
