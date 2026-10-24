package at.technikum.thesis.runner;

public record Observation(
        long seq,
        double scheduledAt,
        double sentAt,
        double receivedAt,
        int status,
        String pod,
        String configVersion,
        long startedAt,
        String error
) {
    public double latencyMillis() {
        return receivedAt - scheduledAt;
    }

    public boolean ok() {
        return error == null && status == 200;
    }
}