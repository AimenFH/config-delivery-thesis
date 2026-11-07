package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record CampaignConfig(
        String name,
        int repetitions,
        int maxAttempts,
        String url,
        int ratePerSecond,
        int expectedReplicas,
        double maxGapMillis,
        List<Way> ways
) {

    public record Way(
            String name,
            String manifests,
            String versionKey,
            List<String> changeCommand,
            int warmupSeconds,
            int durationSeconds,
            int settleSeconds,
            int pauseSeconds
    ) {

        public List<String> changeFor(String version) {
            if (changeCommand == null) {
                return ClusterControl.patchCommand(versionKey, version);
            }
            return changeCommand.stream().map(part -> part.replace("{version}", version)).toList();
        }
    }

    public static CampaignConfig read(Path file) throws IOException {
        CampaignConfig config = new ObjectMapper().readValue(file.toFile(), CampaignConfig.class);
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        return config;
    }

    public URI uri() {
        return URI.create(url);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("name is missing");
        }
        if (repetitions < 1) {
            problems.add("repetitions must be at least 1");
        }
        if (maxAttempts < repetitions) {
            problems.add("maxAttempts must be at least repetitions");
        }
        if (url == null) {
            problems.add("url is missing");
        }
        if (ratePerSecond < 1) {
            problems.add("ratePerSecond must be at least 1");
        }
        if (expectedReplicas < 1) {
            problems.add("expectedReplicas must be at least 1");
        }
        if (maxGapMillis <= 0) {
            problems.add("maxGapMillis must be positive");
        }
        if (ways == null || ways.isEmpty()) {
            problems.add("ways is empty");
            return problems;
        }
        for (Way way : ways) {
            if (way.name() == null || way.manifests() == null) {
                problems.add("every way needs name and manifests");
            }
            if ((way.versionKey() == null) == (way.changeCommand() == null)) {
                problems.add("way " + way.name() + ": needs either versionKey or changeCommand");
            }
            if (way.changeCommand() != null && way.changeCommand().stream().noneMatch(p -> p.contains("{version}"))) {
                problems.add("way " + way.name() + ": changeCommand must contain {version}");
            }
            if (way.durationSeconds() < 1) {
                problems.add("way " + way.name() + ": durationSeconds must be at least 1");
            }
        }
        return problems;
    }
}
