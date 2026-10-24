package at.technikum.thesis.runner;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Runner {

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");

        URI url = URI.create(arg(args, "--url", "http://localhost:30080/probe"));
        int rate = Integer.parseInt(arg(args, "--rate", "50"));
        int seconds = Integer.parseInt(arg(args, "--duration", "10"));
        int replicas = Integer.parseInt(arg(args, "--replicas", "3"));
        double maxGap = Double.parseDouble(arg(args, "--max-gap-ms", "1000"));
        String label = arg(args, "--label", "test");
        Path out = Path.of(arg(args, "--out", "results/raw"));

        RunnerClock clock = new RunnerClock();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + label;
        ProbeClient client = new ProbeClient(url, clock);
        LoadGenerator load = new LoadGenerator(client, clock, rate);

        System.out.printf("Probing %s at %d req/s for %d s%n", url, rate, seconds);
        List<Observation> observations = load.run(Duration.ofSeconds(seconds));
        printSummary(observations, load.scheduledCount());

        CoverageCheck.Result coverage = new CoverageCheck(replicas, maxGap).check(observations);
        printCoverage(coverage);

        RunResult result = new RunResult(runId, label, clock.startIso(), clock.startEpochMillis(),
                url.toString(), rate, seconds, replicas, maxGap,
                load.scheduledCount(), observations.size(),
                observations.stream().filter(Observation::ok).count(),
                coverage.valid(), coverage.problems());
        Path written = new ResultWriter(out).write(result, observations);
        System.out.println("Saved " + written.toAbsolutePath());

        if (!coverage.valid()) {
            System.exit(2);
        }
    }

    private static void printCoverage(CoverageCheck.Result coverage) {
        System.out.println("Coverage per pod:");
        for (CoverageCheck.PodCoverage pod : coverage.pods()) {
            System.out.printf("  %-40s %5d  (%4.1f %%)  longest unobserved %6.0f ms%n",
                    pod.pod(), pod.count(), 100 * pod.share(), pod.maxGapMillis());
        }
        if (coverage.valid()) {
            System.out.println("Run VALID");
        } else {
            System.out.println("Run INVALID");
            coverage.problems().forEach(problem -> System.out.println("  - " + problem));
        }
    }

    private static void printSummary(List<Observation> observations, long scheduled) {
        long ok = observations.stream().filter(Observation::ok).count();
        System.out.printf("Requests: %d scheduled, %d recorded, %d ok, %d failed%n",
                scheduled, observations.size(), ok, observations.size() - ok);

        System.out.println("Per config version:");
        countBy(observations, Observation::configVersion).forEach((version, n) ->
                System.out.printf("  %-40s %5d%n", version, n));

        double[] latencies = observations.stream().filter(Observation::ok)
                .mapToDouble(Observation::latencyMillis).sorted().toArray();
        if (latencies.length > 0) {
            System.out.printf("Latency ms: p50 %.1f  p90 %.1f  p99 %.1f  max %.1f%n",
                    percentile(latencies, 50), percentile(latencies, 90),
                    percentile(latencies, 99), latencies[latencies.length - 1]);
        }
    }

    private static Map<String, Long> countBy(List<Observation> observations, Function<Observation, String> key) {
        return observations.stream().collect(Collectors.groupingBy(
                o -> String.valueOf(key.apply(o)), TreeMap::new, Collectors.counting()));
    }

    private static double percentile(double[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}