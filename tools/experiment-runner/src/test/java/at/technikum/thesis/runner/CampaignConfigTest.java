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
        assertEquals(List.of("way-a", "way-c", "way-b"), config.ways().stream().map(CampaignConfig.Way::name).toList());
        CampaignConfig.Way wayB = config.ways().get(2);
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
        CampaignConfig.Way way = new CampaignConfig.Way("way-a", "deploy/way-a-envvar", "configVersion", null, 5, 90, 30, 60);
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
        CampaignConfig.Way way = new CampaignConfig.Way("way-x", null, null, null, 5, 0, 0, 0);
        CampaignConfig config = new CampaignConfig("test", 1, 1, "http://localhost:30080/probe", 50, 3, 1000, List.of(way));

        List<String> problems = config.problems();

        assertTrue(problems.contains("every way needs name and manifests"), problems.toString());
        assertTrue(problems.contains("way way-x: needs either versionKey or changeCommand"), problems.toString());
        assertTrue(problems.contains("way way-x: durationSeconds must be at least 1"), problems.toString());
    }

    @Test
    void readFailsOnAnInvalidFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{\"name\":\"bad\",\"repetitions\":0,\"ways\":[]}");

        assertThrows(IllegalArgumentException.class, () -> CampaignConfig.read(file));
    }

    @Test
    void wayCRunsItsScriptWithTheVersion() throws Exception {
        CampaignConfig.Way wayC = CampaignConfig.read(CAMPAIGNS.resolve("smoke.json")).ways().get(1);

        assertEquals(List.of("bash", "tools/way-c-change.sh", "v123"), wayC.changeFor("v123"));
    }

    @Test
    void wayWithVersionKeyPatchesTheConfigMap() {
        CampaignConfig.Way way = new CampaignConfig.Way("way-a", "deploy/way-a-envvar", "configVersion", null, 5, 90, 30, 60);

        assertEquals(ClusterControl.patchCommand("configVersion", "v7"), way.changeFor("v7"));
    }

    @Test
    void reportsBothOrNeitherChangeMethodAndAMissingPlaceholder() {
        CampaignConfig.Way both = new CampaignConfig.Way("both", "m", "configVersion", List.of("x", "{version}"), 5, 90, 0, 0);
        CampaignConfig.Way noPlaceholder = new CampaignConfig.Way("fixed", "m", null, List.of("bash", "x.sh", "v1"), 5, 90, 0, 0);
        CampaignConfig config = new CampaignConfig("test", 1, 1, "http://localhost:30080/probe", 50, 3, 1000,
                List.of(both, noPlaceholder));

        List<String> problems = config.problems();

        assertTrue(problems.contains("way both: needs either versionKey or changeCommand"), problems.toString());
        assertTrue(problems.contains("way fixed: changeCommand must contain {version}"), problems.toString());
    }

    @Test
    void versionsGrowWithEveryChangeAndAreValidVersionNames() {
        assertEquals("v1794100001", Campaign.version(1794100000L, 1));
        assertEquals("v1794100002", Campaign.version(1794100000L, 2));
        assertTrue(Campaign.version(1794100000L, 1).matches("v\\d+"));
    }
}
