package at.technikum.thesis.runner;

import java.util.List;

public record RunResult(
        String runId,
        String label,
        String runStartedAt,
        long runStartedAtEpochMillis,
        String url,
        int ratePerSecond,
        int durationSeconds,
        int expectedReplicas,
        double maxGapMillis,
        long scheduled,
        int recorded,
        long ok,
        boolean valid,
        List<String> problems
) {
}
