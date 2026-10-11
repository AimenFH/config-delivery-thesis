package at.technikum.thesis.probe;

public record ProbeResponse(
        String value,
        String configVersion,
        String pod,
        long startedAt,
        long servedAt
) {}
