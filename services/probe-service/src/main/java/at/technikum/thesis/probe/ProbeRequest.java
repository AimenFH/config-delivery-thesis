package at.technikum.thesis.probe;

import java.time.Instant;

public record ProbeRequest(
        long id,
        Instant receivedAt,
        String pod,
        String configVersion
) {}
