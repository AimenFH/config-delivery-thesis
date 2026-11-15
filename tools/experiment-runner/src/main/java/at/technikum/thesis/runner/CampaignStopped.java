package at.technikum.thesis.runner;

public final class CampaignStopped extends RuntimeException {

    public CampaignStopped(String reason) {
        super(reason);
    }
}
