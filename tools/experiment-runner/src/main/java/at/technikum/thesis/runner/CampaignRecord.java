package at.technikum.thesis.runner;

import java.util.List;

public record CampaignRecord(
        String campaignId,
        CampaignConfig config,
        Protocol protocol,
        Scenario scenario,
        List<String> approaches,
        List<List<String>> blocks
) {
}
