package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class Campaign {

    private static final Duration STEADY_TIMEOUT = Duration.ofSeconds(120);

    private final CampaignConfig config;
    private final ClusterControl cluster = new ClusterControl();

    public Campaign(CampaignConfig config) {
        this.config = config;
    }

    public int run() throws Exception {
        String campaignId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + config.name();
        Path dir = Path.of("results/raw", campaignId);
        Files.createDirectories(dir);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(dir.resolve("campaign.json").toFile(), config);
        System.out.printf("Campaign %s: %d ways, %d valid runs each (at most %d attempts), results in %s%n",
                campaignId, config.ways().size(), config.repetitions(), config.maxAttempts(), dir.toAbsolutePath());

        List<Summary.Entry> entries = new ArrayList<>();
        long versionBase = Instant.now().getEpochSecond();
        int changes = 0;
        int exitCode = 0;
        try {
            for (CampaignConfig.Way way : config.ways()) {
                prepare(way);
                int valid = 0;
                for (int attempt = 1; attempt <= config.maxAttempts() && valid < config.repetitions(); attempt++) {
                    String version = version(versionBase, ++changes);
                    String label = String.format("%s-r%02d", way.name(), attempt);
                    System.out.printf("%n== %s, attempt %d of at most %d (%d of %d valid so far), change to %s%n",
                            way.name(), attempt, config.maxAttempts(), valid, config.repetitions(), version);
                    awaitSteady(way, false);
                    RunResult result = Runner.run(new RunSettings(config.uri(), config.ratePerSecond(),
                            way.durationSeconds(), way.warmupSeconds(), config.expectedReplicas(),
                            config.maxGapMillis(), label, dir, way.changeFor(version)));
                    entries.add(new Summary.Entry(way.name(), result));
                    if (result.valid()) {
                        valid++;
                    }
                    sleep(way.pauseSeconds());
                }
                if (valid < config.repetitions()) {
                    System.out.printf("%n%s: only %d of %d valid runs after %d attempts%n",
                            way.name(), valid, config.repetitions(), config.maxAttempts());
                }
            }
        } catch (IllegalStateException e) {
            System.out.println("Campaign stopped: " + e.getMessage());
            exitCode = 1;
        }

        Summary summary = Summary.of(entries);
        System.out.println();
        summary.print(System.out);
        summary.writeCsv(dir.resolve("summary.csv"));
        System.out.println("Saved " + dir.resolve("summary.csv").toAbsolutePath());
        if (exitCode == 0 && !summary.reached(config.repetitions())) {
            exitCode = 2;
        }
        return exitCode;
    }

    static String version(long base, int change) {
        return "v" + (base + change);
    }

    private void prepare(CampaignConfig.Way way) throws InterruptedException {
        System.out.printf("%n== %s: switching to %s%n", way.name(), way.manifests());
        cluster.switchTo(way.manifests());
        System.out.printf("  settling for %d s%n", way.settleSeconds());
        sleep(way.settleSeconds());
        awaitSteady(way, true);
    }

    private void awaitSteady(CampaignConfig.Way way, boolean required) throws InterruptedException {
        ClusterControl.Snapshot snapshot = cluster.awaitSteady(config.uri(), config.expectedReplicas(), STEADY_TIMEOUT);
        boolean steady = ClusterControl.isSteady(snapshot, config.expectedReplicas());
        System.out.printf("  %s: %d pods, versions %s, %d failed, slowest %.0f ms%n",
                steady ? "steady" : "NOT steady", snapshot.pods().size(), snapshot.versions(),
                snapshot.failed(), snapshot.maxLatencyMillis());
        if (!steady && required) {
            throw new IllegalStateException(way.name() + " did not become steady within " + STEADY_TIMEOUT.toSeconds() + " s");
        }
    }

    private static void sleep(int seconds) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(seconds).toMillis());
    }
}
