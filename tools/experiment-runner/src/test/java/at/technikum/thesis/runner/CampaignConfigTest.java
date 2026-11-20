package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CampaignConfigTest {

    private static final Path CAMPAIGNS = Fixtures.ROOT.resolve("campaigns");

    @Test
    void theThreeCampaignsNeedTwentyAndTenValidRunsInAtMostTwentyFiveBlocks() throws Exception {
        assertEquals(new CampaignConfig("s1", "protocol/protocol-v1.json", "scenarios/s1-valid-change.json",
                20, 25, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s1.json")));
        assertEquals(new CampaignConfig("s2", "protocol/protocol-v1.json", "scenarios/s2-invalid-config.json",
                10, 25, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s2.json")));
        assertEquals(new CampaignConfig("s3", "protocol/protocol-v1.json", "scenarios/s3-infra-outage.json",
                10, 25, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s3.json")));
    }

    @Test
    void theSmokeCampaignsNeedTwoBlocksAndOneBlock() throws Exception {
        assertEquals(new CampaignConfig("s1-smoke", "protocol/protocol-v1.json", "scenarios/s1-valid-change.json",
                2, 2, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s1-smoke.json")));
        assertEquals(new CampaignConfig("s2-smoke", "protocol/protocol-v1.json", "scenarios/s2-invalid-config.json",
                1, 1, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s2-smoke.json")));
        assertEquals(new CampaignConfig("s3-smoke", "protocol/protocol-v1.json", "scenarios/s3-infra-outage.json",
                1, 1, 20261115L), CampaignConfig.read(CAMPAIGNS.resolve("s3-smoke.json")));
    }

    @Test
    void everyCampaignLoadsItsProtocolScenarioAndTheFourApproaches() throws Exception {
        for (String name : List.of("s1", "s2", "s3", "s1-smoke", "s2-smoke", "s3-smoke")) {
            CampaignConfig config = CampaignConfig.read(CAMPAIGNS.resolve(name + ".json"));

            CampaignConfig.Loaded loaded = config.load(Fixtures.ROOT);

            assertEquals(config, loaded.config());
            assertEquals(Fixtures.protocol(), loaded.protocol());
            assertEquals(Scenario.read(Fixtures.ROOT.resolve(config.scenario())), loaded.scenario());
            assertEquals(List.of("way-a", "way-b", "way-c", "way-d"),
                    loaded.approaches().stream().map(ApproachDescriptor::name).toList());
        }
    }

    @Test
    void reportsMissingFields() {
        CampaignConfig config = new CampaignConfig(null, null, null, 0, 0, 0);

        assertEquals(List.of("name is missing", "protocol is missing", "scenario is missing",
                "validRuns must be at least 1"), config.problems());
    }

    @Test
    void validRunsMustFitIntoTheBlocks() {
        CampaignConfig config = new CampaignConfig("test", "p.json", "s.json", 21, 20, 1);

        assertEquals(List.of("validRuns must be at most maxBlocks"), config.problems());
    }

    @Test
    void aCampaignCannotRunMoreBlocksThanTheProtocolAllows() throws Exception {
        CampaignConfig tooMany = new CampaignConfig("test", "p.json", "s.json", 20, 26, 1);
        CampaignConfig atTheLimit = new CampaignConfig("test", "p.json", "s.json", 20, 25, 1);

        assertEquals(List.of("maxBlocks must be at most 25, the limit of the protocol"),
                tooMany.problems(Fixtures.protocol()));
        assertEquals(List.of(), atTheLimit.problems(Fixtures.protocol()));
    }

    @Test
    void loadFailsWhenTheCampaignExceedsTheProtocol() {
        CampaignConfig config = new CampaignConfig("test", "protocol/protocol-v1.json",
                "scenarios/s1-valid-change.json", 20, 26, 1);

        assertThrows(IllegalArgumentException.class, () -> config.load(Fixtures.ROOT));
    }

    @Test
    void readFailsOnAnInvalidFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{\"name\":\"bad\",\"protocol\":\"p.json\",\"scenario\":\"s.json\","
                + "\"validRuns\":0,\"maxBlocks\":1,\"seed\":1}");

        assertThrows(IllegalArgumentException.class, () -> CampaignConfig.read(file));
    }

    @Test
    void readFailsOnTheTimingFieldsOfTheOldFormat(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("old.json");
        Files.writeString(file, "{\"name\":\"old\",\"protocol\":\"p.json\",\"scenario\":\"s.json\","
                + "\"validRuns\":1,\"maxBlocks\":1,\"seed\":1,\"durationSeconds\":90}");

        assertThrows(Exception.class, () -> CampaignConfig.read(file));
        assertThrows(Exception.class, () -> CampaignConfig.read(CAMPAIGNS.resolve("legacy/scenario-1.json")));
    }
}
