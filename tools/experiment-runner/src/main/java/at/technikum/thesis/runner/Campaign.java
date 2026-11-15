package at.technikum.thesis.runner;

public final class Campaign {

    private final CampaignConfig config;

    public Campaign(CampaignConfig config) {
        this.config = config;
    }

    public int run() throws Exception {
        throw new UnsupportedOperationException("built in phase 2");
    }
}
