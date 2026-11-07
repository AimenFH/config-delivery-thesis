package at.technikum.thesis.runner;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public record RunSettings(
        URI url,
        int ratePerSecond,
        int durationSeconds,
        int warmupSeconds,
        int expectedReplicas,
        double maxGapMillis,
        String label,
        Path out,
        List<String> changeCommand
) {
}
