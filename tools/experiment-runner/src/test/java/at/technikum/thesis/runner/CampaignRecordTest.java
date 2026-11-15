package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CampaignRecordTest {

    @Test
    void aCampaignRecordSurvivesWritingAndReading() throws Exception {
        for (String name : List.of("s1", "s2", "s3")) {
            CampaignConfig.Loaded loaded = CampaignConfig.read(Fixtures.ROOT.resolve("campaigns/" + name + ".json"))
                    .load(Fixtures.ROOT);
            CampaignRecord record = new CampaignRecord("20261115-200000_" + name, loaded.config(), loaded.protocol(),
                    loaded.scenario(), List.of("way-a", "way-b", "way-c"),
                    List.of(List.of("way-b", "way-a", "way-c"), List.of("way-c", "way-b", "way-a")));

            String json = Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record);

            assertEquals(record, Fixtures.JSON.readValue(json, CampaignRecord.class));
        }
    }
}
