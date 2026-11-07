package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignConfigTest {

    private static final Path CAMPAIGNS = Path.of("../../campaigns");

    @Test
    void readsTheSmokeCampaign() throws Exception {
        CampaignConfig config = CampaignConfig.read(CAMPAIGNS.resolve("smoke.json"));

        assertEquals("smoke", config.name());
        assertEquals(2, config.repetitions());
        assertEquals(4, config.maxAttempts());
        assertEquals(2, config.ways().size());
        CampaignConfig.Way wayB = config.ways().get(1);
        assertEquals("demo.configVersion", wayB.versionKey());
        assertEquals(100, wayB.settleSeconds());
    }

    @Test
    void scenarioOneHasFifteenRepetitions() throws Exception {
        CampaignConfig config = CampaignConfig.read(CAMPAIGNS.resolve("scenario-1.json"));

        assertEquals(15, config.repetitions());
        assertEquals(20, config.maxAttempts());
    }

    @Test
    void reportsFewerAttemptsThanRepetitions() {
        CampaignConfig.Way way = new CampaignConfig.Way("way-a", "deploy/way-a-envvar", "configVersion", 5, 90, 30, 60);
        CampaignConfig config = new CampaignConfig("test", 15, 10, "http://localhost:30080/probe", 50, 3, 1000, List.of(way));

        assertEquals(List.of("maxAttempts must be at least repetitions"), config.problems());
    }

    @Test
    void reportsMissingFields() {
        CampaignConfig config = new CampaignConfig(null, 0, 0, null, 0, 0, 0, List.of());

        List<String> problems = config.problems();

        assertTrue(problems.contains("name is missing"), problems.toString());
        assertTrue(problems.contains("repetitions must be at least 1"), problems.toString());
        assertTrue(problems.contains("ways is empty"), problems.toString());
    }

    @Test
    void reportsAnIncompleteWay() {
        CampaignConfig.Way way = new CampaignConfig.Way("way-x", null, null, 5, 0, 0, 0);
        CampaignConfig config = new CampaignConfig("test", 1, 1, "http://localhost:30080/probe", 50, 3, 1000, List.of(way));

        List<String> problems = config.problems();

        assertTrue(problems.contains("every way needs name, manifests and versionKey"), problems.toString());
        assertTrue(problems.contains("way way-x: durationSeconds must be at least 1"), problems.toString());
    }

    @Test
    void readFailsOnAnInvalidFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{\"name\":\"bad\",\"repetitions\":0,\"ways\":[]}");

        assertThrows(IllegalArgumentException.class, () -> CampaignConfig.read(file));
    }
}
