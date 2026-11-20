package at.technikum.thesis.runner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Runner {

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");

        String campaign = arg(args, "--campaign", null);
        if (campaign != null) {
            System.exit(campaign(Path.of(campaign)));
        }
        String evaluate = arg(args, "--evaluate", null);
        if (evaluate != null) {
            System.exit(Wiring.evaluate(Path.of(evaluate)));
        }
        String loki = arg(args, "--loki", null);
        if (loki != null) {
            System.exit(LokiExport.campaign(Path.of(loki),
                    URI.create(arg(args, "--loki-url", "http://localhost:3100")),
                    arg(args, "--grafana-url", "http://localhost:3000")));
        }

        RunSettings settings = new RunSettings(
                URI.create(arg(args, "--url", "http://localhost:30080/probe")),
                Integer.parseInt(arg(args, "--rate", "50")),
                Integer.parseInt(arg(args, "--duration", "10")),
                Integer.parseInt(arg(args, "--warmup", "5")),
                Integer.parseInt(arg(args, "--replicas", "3")),
                Double.parseDouble(arg(args, "--max-gap-ms", "1000")),
                arg(args, "--label", "test"),
                Path.of(arg(args, "--out", "results/raw")),
                commandAfterSeparator(args));

        if (!run(settings).valid()) {
            System.exit(2);
        }
    }

    static final class Wiring {

        private Wiring() {
        }

        static RunJudge judge(CampaignConfig.Loaded loaded) {
            return new Evaluator(loaded.protocol(), loaded.scenario(), loaded.approaches());
        }

        static RunStore store(CampaignConfig.Loaded loaded, Path campaignDirectory) {
            return new ResultWriter(campaignDirectory);
        }

        static Sampler sampler(CampaignConfig.Loaded loaded) {
            return new ResourceSampler(loaded.protocol().resourceSamplingSeconds(), CommandRunner.process());
        }

        static int evaluate(Path campaignDirectory) throws IOException {
            List<RunMetrics> runs = Evaluator.evaluateCampaign(campaignDirectory);
            long valid = runs.stream().filter(run -> run.verdict() == RunMetrics.Verdict.VALID).count();
            System.out.printf("Evaluated %d runs (%d valid) in %s%n", runs.size(), valid, campaignDirectory.toAbsolutePath());
            System.out.println("Saved " + campaignDirectory.resolve(Evaluator.RUNS_FILE).toAbsolutePath());
            System.out.println("Saved " + campaignDirectory.resolve(Evaluator.SUMMARY_FILE).toAbsolutePath());
            return 0;
        }
    }

    static int campaign(Path file) throws Exception {
        CampaignConfig.Loaded loaded = CampaignConfig.read(file).load();
        AtomicReference<Path> campaignDirectory = new AtomicReference<>();
        int exitCode = new Campaign(loaded, () -> Wiring.sampler(loaded), Wiring.judge(loaded), directory -> {
            campaignDirectory.set(directory);
            return Wiring.store(loaded, directory);
        }).run();
        if (campaignDirectory.get() != null) {
            Wiring.evaluate(campaignDirectory.get());
        }
        return exitCode;
    }

    public static RunResult run(RunSettings settings) throws Exception {
        RunnerClock clock = new RunnerClock();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + settings.label();
        ProbeClient client = new ProbeClient(settings.url(), clock);
        LoadGenerator load = new LoadGenerator(client, clock, settings.ratePerSecond());

        System.out.printf("Probing %s at %d req/s: %d s warm-up, then %d s%n",
                settings.url(), settings.ratePerSecond(), settings.warmupSeconds(), settings.durationSeconds());
        CompletableFuture<ChangeTrigger.Result> change = settings.changeCommand().isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(new ChangeTrigger(settings.changeCommand(), clock)::fire,
                        CompletableFuture.delayedExecutor(settings.warmupSeconds(), TimeUnit.SECONDS));
        List<Observation> observations = load.run(Duration.ofSeconds(settings.warmupSeconds() + settings.durationSeconds()));
        ChangeTrigger.Result changeResult = change.join();
        printSummary(observations, load.scheduledCount());
        printChange(changeResult);

        CoverageCheck.Result coverage = new CoverageCheck(settings.expectedReplicas(), settings.maxGapMillis())
                .check(observations);
        printCoverage(coverage);

        List<String> problems = new ArrayList<>(coverage.problems());
        if (changeResult != null && changeResult.exitCode() != 0) {
            problems.add("change command failed with exit code " + changeResult.exitCode() + ": " + changeResult.output());
        }
        WindowDetector.Result window = changeResult == null ? null
                : new WindowDetector(settings.maxGapMillis()).detect(observations, changeResult);
        if (window != null) {
            printWindow(window);
            problems.addAll(window.problems());
        }
        boolean valid = problems.isEmpty();
        printVerdict(valid, problems);
        if (window != null && !window.flags().isEmpty()) {
            System.out.println("Flags: " + window.flags().stream().map(Enum::name).collect(Collectors.joining(" ")));
        }

        RunResult result = new RunResult(runId, settings.label(), clock.startIso(), clock.startEpochMillis(),
                settings.url().toString(), settings.ratePerSecond(), settings.durationSeconds(),
                settings.warmupSeconds(), settings.expectedReplicas(), settings.maxGapMillis(),
                load.scheduledCount(), observations.size(),
                observations.stream().filter(Observation::ok).count(),
                valid, problems, changeResult, window);
        Path written = new ResultWriter(settings.out()).write(result, observations);
        System.out.println("Saved " + written.toAbsolutePath());
        return result;
    }

    private static void printChange(ChangeTrigger.Result change) {
        if (change == null) {
            System.out.println("Change: none");
            return;
        }
        System.out.printf("Change: exit %d, started %.1f ms, finished %.1f ms%n",
                change.exitCode(), change.startedAt(), change.finishedAt());
        if (!change.output().isEmpty()) {
            System.out.println("  " + change.output());
        }
    }

    private static void printWindow(WindowDetector.Result window) {
        System.out.printf("Window: %s -> %s%n", window.oldVersion(), window.newVersion());
        if (window.windowMillis() == null) {
            System.out.println("  not measurable");
            return;
        }
        System.out.printf("  first new    %10.1f ms  (propagation %.1f ms after the change)%n",
                window.firstNewAt(), window.propagationMillis());
        System.out.printf("  last old     %10.1f ms  (convergence %.1f ms after the change)%n",
                window.lastOldAt(), window.convergenceMillis());
        System.out.printf("  window       %10.1f ms  (at most %.1f ms)%n",
                window.windowMillis(), window.windowMaxMillis());
        System.out.printf("  failed requests after the change: %d%n", window.failedAfterChange());
    }

    private static void printCoverage(CoverageCheck.Result coverage) {
        System.out.println("Coverage per pod:");
        for (CoverageCheck.PodCoverage pod : coverage.pods()) {
            System.out.printf("  %-40s %5d  (%4.1f %%)  longest unobserved %6.0f ms%n",
                    pod.pod(), pod.count(), 100 * pod.share(), pod.maxGapMillis());
        }
    }

    private static void printVerdict(boolean valid, List<String> problems) {
        if (valid) {
            System.out.println("Run VALID");
        } else {
            System.out.println("Run INVALID");
            problems.forEach(problem -> System.out.println("  - " + problem));
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
        int end = separatorIndex(args);
        for (int i = 0; i < end - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static List<String> commandAfterSeparator(String[] args) {
        int separator = separatorIndex(args);
        return separator == args.length ? List.of() : List.of(args).subList(separator + 1, args.length);
    }

    private static int separatorIndex(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--")) {
                return i;
            }
        }
        return args.length;
    }
}