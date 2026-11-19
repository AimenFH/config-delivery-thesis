package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class Evaluator implements RunJudge {

    public static final String CAMPAIGN_FILE = "campaign.json";
    public static final String APPROACHES_FOLDER = "approaches";
    public static final String RUNS_FILE = "runs.csv";
    public static final String SUMMARY_FILE = "summary.csv";

    private static final Set<String> COLUMNS_WITH_TEXT = Set.of("O5_extraComponents", "U2");

    private final Protocol protocol;
    private final Scenario scenario;
    private final Map<String, ApproachDescriptor> approaches = new LinkedHashMap<>();

    public Evaluator(Protocol protocol, Scenario scenario, List<ApproachDescriptor> approaches) {
        this.protocol = protocol;
        this.scenario = scenario;
        for (ApproachDescriptor approach : approaches) {
            this.approaches.put(approach.name(), approach);
        }
    }

    public static List<String> columns(MetricId id) {
        return switch (id) {
            case C1 -> List.of("C1", "C1_max");
            case F2 -> List.of("F2_p50_B", "F2_p95_B", "F2_p99_B", "F2_max_B",
                    "F2_p50_H", "F2_p95_H", "F2_p99_H", "F2_max_H", "F2_p99_ratio");
            case O3 -> List.of("O3_cpu", "O3_memory");
            case O5 -> List.of("O5_extraComponents", "O5_operatorActions", "O5_clusterWideRbac",
                    "O5_serviceCoupling", "O5_requestedCpu", "O5_requestedMemory");
            case E3 -> List.of("E3_ratio", "E3_outage");
            default -> List.of(id.name());
        };
    }

    public static List<String> columns(Scenario scenario) {
        return scenario.reportedMetrics().stream().flatMap(id -> columns(id).stream()).toList();
    }

    public static List<RunMetrics> evaluateCampaign(Path campaignDir) throws IOException {
        CampaignRecord campaign = new ObjectMapper()
                .readValue(campaignDir.resolve(CAMPAIGN_FILE).toFile(), CampaignRecord.class);
        List<ApproachDescriptor> descriptors = new ArrayList<>();
        for (String name : campaign.approaches()) {
            descriptors.add(ApproachDescriptor.read(campaignDir.resolve(APPROACHES_FOLDER).resolve(name + ".json")));
        }
        Evaluator evaluator = new Evaluator(campaign.protocol(), campaign.scenario(), descriptors);
        ResultWriter store = new ResultWriter(campaignDir);

        List<String> columns = columns(campaign.scenario());
        List<List<String>> rows = new ArrayList<>();
        rows.add(header(columns));
        List<RunMetrics> all = new ArrayList<>();
        for (String runId : store.runIds()) {
            RunRecord record = store.read(runId);
            RunMetrics metrics = evaluator.evaluate(record);
            rows.add(row(record, metrics, columns));
            all.add(metrics);
        }
        Csv.write(campaignDir.resolve(RUNS_FILE), rows);
        Summary.of(all, campaign.approaches(), campaign.protocol().bootstrapResamples(),
                campaign.protocol().bootstrapSeed())
                .writeCsv(campaignDir.resolve(SUMMARY_FILE), campaign.protocol().protocolVersion());
        return all;
    }

    static List<String> header(List<String> columns) {
        List<String> header = new ArrayList<>(List.of("protocolVersion",
                "run_id", "approach", "block", "position", "attempt", "verdict", "reasons", "flags"));
        for (String column : columns) {
            header.add(column);
            if (COLUMNS_WITH_TEXT.contains(column)) {
                header.add(column + "_text");
            }
        }
        return header;
    }

    static List<String> row(RunRecord record, RunMetrics metrics, List<String> columns) {
        Map<String, Metric> byColumn = new LinkedHashMap<>();
        for (Metric metric : metrics.metrics()) {
            byColumn.put(metric.column(), metric);
        }
        List<String> row = new ArrayList<>();
        row.add(Integer.toString(record.protocolVersion()));
        row.add(metrics.runId());
        row.add(metrics.approach());
        row.add(Integer.toString(record.block()));
        row.add(Integer.toString(record.positionInBlock()));
        row.add(Integer.toString(record.attempt()));
        row.add(metrics.verdict().name());
        row.add(String.join("; ", metrics.invalidReasons()));
        row.add(new TreeSet<>(metrics.flags()).stream().map(Enum::name).collect(Collectors.joining(" ")));
        for (String column : columns) {
            Metric metric = byColumn.get(column);
            if (COLUMNS_WITH_TEXT.contains(column)) {
                row.add(metric == null || metric.value() == null ? "" : Csv.metric(metric));
                row.add(metric == null || metric.text() == null ? "" : metric.text());
            } else {
                row.add(Csv.metric(metric));
            }
        }
        return row;
    }

    @Override
    public RunMetrics evaluate(RunRecord record) {
        Set<MetricId> reported = scenario.reportedMetrics();
        List<String> reasons = new ArrayList<>();
        Set<RunMetrics.Flag> flags = EnumSet.noneOf(RunMetrics.Flag.class);
        List<Metric> metrics = new ArrayList<>();
        if (record.reference() == null) {
            reasons.add(String.format(Locale.ROOT,
                    "no step was started, so the baseline is undefined: 0 of %d replicas were observed in it",
                    protocol.replicas()));
            reasons.addAll(commitProblems(record));
            for (MetricId id : reported) {
                for (String column : columns(id)) {
                    metrics.add(new Metric(id, column, null, false, null));
                }
            }
        } else {
            Run run = new Run(record);
            reasons.addAll(run.instrumentProblems());
            for (MetricId id : reported) {
                metrics.addAll(run.metrics(id));
            }
            flags.addAll(run.flags(reported, metrics));
        }
        return new RunMetrics(record.runId(), record.approach(), record.scenario(), record.block(),
                reasons.isEmpty() ? RunMetrics.Verdict.VALID : RunMetrics.Verdict.INVALID,
                List.copyOf(reasons), flags, commitSucceeded(record), List.copyOf(metrics));
    }

    private long scenarioCommits() {
        return scenario.steps().stream().filter(step -> step.action() == Scenario.Action.COMMIT).count();
    }

    private boolean commitSucceeded(RunRecord record) {
        List<RunRecord.StepRecord> commits = record.commits();
        return commits.size() >= scenarioCommits() && commits.stream().allMatch(commit -> commit.exitCode() == 0);
    }

    private List<String> commitProblems(RunRecord record) {
        List<String> problems = new ArrayList<>();
        List<RunRecord.StepRecord> commits = record.commits();
        for (RunRecord.StepRecord commit : commits) {
            String version = commit.payload() == null ? "step " + commit.index() : commit.payload().configVersion();
            if (commit.exitCode() != 0) {
                problems.add(String.format(Locale.ROOT, "commit of %s failed with exit code %d",
                        version, commit.exitCode()));
            }
            if (triggerFailed(commit) && !duringOutage(record, commit)) {
                problems.add(String.format(Locale.ROOT, "trigger after the commit of %s failed with exit code %d",
                        version, commit.trigger().exitCode()));
            }
        }
        for (long missing = commits.size() + 1; missing <= scenarioCommits(); missing++) {
            problems.add(String.format(Locale.ROOT, "commit %d of the scenario did not run", missing));
        }
        return problems;
    }

    private static boolean triggerFailed(RunRecord.StepRecord commit) {
        return commit.trigger() != null && commit.trigger().exitCode() != 0;
    }

    private static boolean duringOutage(RunRecord record, RunRecord.StepRecord commit) {
        boolean down = false;
        for (RunRecord.StepRecord step : record.steps()) {
            if (step.index() == commit.index()) {
                return down;
            }
            if (step.action() == Scenario.Action.INFRA_DOWN) {
                down = true;
            } else if (step.action() == Scenario.Action.INFRA_UP) {
                down = false;
            }
        }
        return false;
    }

    private static Metric metric(MetricId id, String column, Number value) {
        return new Metric(id, column, value == null ? null : Double.valueOf(value.doubleValue()), false, null);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private final class Run {

        private final RunRecord record;
        private final List<Observation> observations;
        private final List<ResourceSample> resources;
        private final Interval baseline;
        private final Interval observation;
        private final Interval measured;
        private final Interval afterBaseline;
        private final Interval overheadInterval;
        private final Availability availability;
        private final WindowDetector detector;
        private final CoverageCheck coverage;
        private final List<String> declaredVersions;
        private final String oldVersion;
        private final String newVersion;
        private final WindowDetector.Window window;

        private Run(RunRecord record) {
            this.record = record;
            this.observations = orEmpty(record.observations());
            this.resources = orEmpty(record.resources());
            Phases phases = Phases.of(protocol, record.reference());
            this.baseline = phases.baseline();
            this.observation = phases.observation();
            this.measured = new Interval(baseline.from(), record.runEndAt());
            this.afterBaseline = new Interval(record.reference(), record.runEndAt());
            this.overheadInterval = new Interval(baseline.from(), observation.to());
            this.availability = new Availability(observations, record.loadStartedAt(), record.scheduled(),
                    protocol.ratePerSecond());
            this.detector = new WindowDetector(protocol.coverageLimitMillis());
            this.coverage = new CoverageCheck(protocol.replicas(), protocol.coverageLimitMillis());
            this.declaredVersions = List.copyOf(scenario.declaredVersions(protocol));
            this.oldVersion = protocol.initialVersion();
            List<Payload> payloads = scenario.payloads();
            this.newVersion = payloads.isEmpty() ? null : payloads.get(0).configVersion();
            this.window = record.tCs() == null || newVersion == null
                    ? WindowDetector.Window.unobserved()
                    : detector.window(observations, oldVersion, newVersion, record.tCs());
        }

        private List<String> instrumentProblems() {
            List<String> problems = new ArrayList<>();
            for (String problem : coverage.check(observations, baseline).problems()) {
                problems.add("baseline: " + problem);
            }
            long failedInBaseline = availability.failedIn(baseline);
            if (failedInBaseline > 0) {
                problems.add(String.format(Locale.ROOT, "baseline: %d failed requests", failedInBaseline));
            }
            Set<String> baselineVersions = versions(baseline);
            if (baselineVersions.size() > 1) {
                problems.add("baseline: more than one version " + baselineVersions);
            }
            problems.addAll(commitProblems(record));
            problems.addAll(scheduleLagProblems());
            long scheduled = availability.scheduledIn(measured);
            long missing = availability.missingIn(measured);
            if (missing > protocol.unsentLimitRatio() * scheduled) {
                problems.add(String.format(Locale.ROOT,
                        "%d of %d scheduled requests have no observation (limit ratio %s)",
                        missing, scheduled, Csv.number(protocol.unsentLimitRatio())));
            }
            Set<String> undeclared = versions(baseline);
            undeclared.removeAll(declaredVersions);
            Set<String> undeclaredAfterBaseline = versions(afterBaseline);
            undeclaredAfterBaseline.removeAll(declaredVersions);
            if (protocol.unconfiguredVersion() != null) {
                undeclaredAfterBaseline.remove(protocol.unconfiguredVersion());
            }
            undeclared.addAll(undeclaredAfterBaseline);
            if (!undeclared.isEmpty()) {
                problems.add("versions that no payload of the scenario declares " + undeclared);
            }
            problems.addAll(samplingProblems());
            return problems;
        }

        private Set<String> versions(Interval interval) {
            return Availability.answers(observations, interval).stream()
                    .map(Observation::configVersion)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        private List<String> scheduleLagProblems() {
            double limit = protocol.scheduleLagLimitMillis();
            List<Observation> late = observations.stream()
                    .filter(o -> measured.contains(o.intendedAt()))
                    .filter(o -> o.scheduledAt() - o.intendedAt() > limit)
                    .toList();
            if (late.isEmpty()) {
                return List.of();
            }
            double highest = late.stream().mapToDouble(o -> o.scheduledAt() - o.intendedAt()).max().orElseThrow();
            return List.of(String.format(Locale.ROOT,
                    "schedule lag over %d ms for %d requests, highest %s ms",
                    protocol.scheduleLagLimitMillis(), late.size(), Csv.number(highest)));
        }

        private List<String> samplingProblems() {
            if (resources.isEmpty()) {
                return List.of("no resource samples");
            }
            double limit = protocol.resourceGapLimitSeconds() * 1000.0;
            Map<String, TreeSet<Double>> timesByNode = new TreeMap<>();
            for (ResourceSample sample : resources) {
                timesByNode.computeIfAbsent(String.valueOf(sample.node()), node -> new TreeSet<>()).add(sample.at());
            }
            if (record.cluster() != null) {
                List<RunRecord.PodInfo> pods = new ArrayList<>(orEmpty(record.cluster().podsBefore()));
                pods.addAll(orEmpty(record.cluster().podsAfter()));
                for (RunRecord.PodInfo pod : pods) {
                    if (pod != null && pod.node() != null) {
                        timesByNode.computeIfAbsent(pod.node(), node -> new TreeSet<>());
                    }
                }
            }
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, TreeSet<Double>> node : timesByNode.entrySet()) {
                TreeSet<Double> times = node.getValue();
                if (times.isEmpty()) {
                    problems.add(String.format(Locale.ROOT,
                            "resource sampling: node %s runs pods of the pod list and has no sample",
                            node.getKey()));
                    continue;
                }
                if (distance(times, measured.from()) > limit) {
                    problems.add(String.format(Locale.ROOT,
                            "resource sampling: node %s has no sample within %d s of the start of the measured run",
                            node.getKey(), protocol.resourceGapLimitSeconds()));
                }
                if (distance(times, measured.to()) > limit) {
                    problems.add(String.format(Locale.ROOT,
                            "resource sampling: node %s has no sample within %d s of the end of the measured run",
                            node.getKey(), protocol.resourceGapLimitSeconds()));
                }
                double longest = 0;
                Double previous = null;
                for (double at : times) {
                    if (!measured.contains(at)) {
                        continue;
                    }
                    if (previous != null) {
                        longest = Math.max(longest, at - previous);
                    }
                    previous = at;
                }
                if (longest > limit) {
                    problems.add(String.format(Locale.ROOT,
                            "resource sampling: node %s has a gap of %s ms (limit %d s)",
                            node.getKey(), Csv.number(longest), protocol.resourceGapLimitSeconds()));
                }
            }
            return problems;
        }

        private double distance(TreeSet<Double> times, double at) {
            Double before = times.floor(at);
            Double after = times.ceiling(at);
            double toBefore = before == null ? Double.POSITIVE_INFINITY : at - before;
            double toAfter = after == null ? Double.POSITIVE_INFINITY : after - at;
            return Math.min(toBefore, toAfter);
        }

        private Set<RunMetrics.Flag> flags(Set<MetricId> reported, List<Metric> metrics) {
            Set<RunMetrics.Flag> flags = EnumSet.noneOf(RunMetrics.Flag.class);
            if (reported.contains(MetricId.P1) && detector.stillServed(observations, oldVersion, window, observation)) {
                flags.add(RunMetrics.Flag.NOT_CONVERGED);
            }
            if (Consistency.regressions(observations, afterBaseline, declaredVersions) > 0) {
                flags.add(RunMetrics.Flag.REGRESSION);
            }
            if (availability.failedIn(reported.contains(MetricId.A1) ? observation : afterBaseline) > 0) {
                flags.add(RunMetrics.Flag.FAILED_REQUESTS);
            }
            if (coverage.hasGap(observations, afterBaseline)) {
                flags.add(RunMetrics.Flag.LOW_RESOLUTION);
            }
            if (metrics.stream().anyMatch(metric -> metric.id() == MetricId.R1 && metric.censored())) {
                flags.add(RunMetrics.Flag.NOT_RECOVERED);
            }
            if (metrics.stream().anyMatch(metric -> metric.id() == MetricId.U4 && metric.censored())) {
                flags.add(RunMetrics.Flag.LOST_UPDATE);
            }
            if (protocol.unconfiguredVersion() != null
                    && versions(afterBaseline).contains(protocol.unconfiguredVersion())) {
                flags.add(RunMetrics.Flag.UNCONFIGURED);
            }
            if (record.commits().stream().anyMatch(commit -> triggerFailed(commit) && duringOutage(record, commit))) {
                flags.add(RunMetrics.Flag.TRIGGER_FAILED);
            }
            return flags;
        }

        private List<Metric> metrics(MetricId id) {
            return switch (id) {
                case P1 -> List.of(Propagation.convergence(window, record.t0(),
                        detector.stillServed(observations, oldVersion, window, observation), observation));
                case P2 -> List.of(Propagation.firstActivation(window, record.t0(), observation));
                case P3 -> List.of(Propagation.commitDuration(record.tCs(), record.t0()));
                case C1 -> Consistency.window(window,
                        detector.stillServed(observations, oldVersion, window, observation));
                case C2 -> List.of(metric(id, "C2",
                        Consistency.reversions(observations, afterBaseline, oldVersion, newVersion)));
                case C3 -> List.of(metric(id, "C3",
                        Consistency.regressions(observations, afterBaseline, declaredVersions)));
                case A1 -> List.of(metric(id, "A1", availability.failedRatio(observation)));
                case A2 -> List.of(metric(id, "A2", availability.longestOutageMillis(observation)));
                case A3 -> List.of(metric(id, "A3", availability.lowestServingPods(observation)));
                case F1 -> List.of(metric(id, "F1",
                        Performance.excessSlowRequests(observations, baseline, observation)));
                case F2 -> Performance.percentiles(observations, baseline, observation);
                case F3 -> List.of(metric(id, "F3", Performance.worstSecond(observations, observation)));
                case O1 -> List.of(metric(id, "O1", infrastructureUsage().cpuMillicores()));
                case O2 -> List.of(metric(id, "O2", infrastructureUsage().memoryMiB()));
                case O3 -> workloadCost();
                case O4 -> List.of(metric(id, "O4", Overhead.podsReplaced(observations, baseline, observation,
                        protocol.coverageLimitMillis())));
                case O5 -> Overhead.declared(descriptor(), record.cluster());
                case E1 -> List.of(metric(id, "E1", InvalidConfiguration.exposedPods(observations, afterBaseline,
                        scenario.invalidVersions())));
                case E2 -> List.of(metric(id, "E2", InvalidConfiguration.exposedRequests(observations, afterBaseline,
                        scenario.invalidVersions())));
                case E3 -> underTheBadConfiguration();
                case E4 -> List.of(metric(id, "E4", availability.lowestServingPods(measured)));
                case R1 -> List.of(recovery());
                case U1 -> List.of(metric(id, "U1", deliveredDuringOutage()));
                case U2 -> List.of(replacementPod());
                case U3 -> List.of(metric(id, "U3",
                        InfrastructureOutage.mixedVersionMillis(observations, afterBaseline)));
                case U4 -> List.of(InfrastructureOutage.catchUp(window,
                        detector.stillServed(observations, oldVersion, window, afterBaseline),
                        step(Scenario.Action.INFRA_UP) == null ? null : step(Scenario.Action.INFRA_UP).finishedAt(),
                        record.runEndAt()));
                case U5 -> List.of(metric(id, "U5", availability.failedRatio(afterBaseline)));
            };
        }

        private ApproachDescriptor descriptor() {
            ApproachDescriptor descriptor = approaches.get(record.approach());
            if (descriptor == null) {
                throw new IllegalArgumentException("run " + record.runId() + ": no descriptor for approach "
                        + record.approach());
            }
            return descriptor;
        }

        private ResourceUsage.Usage infrastructureUsage() {
            return Overhead.infrastructure(resources, overheadInterval, descriptor().infrastructure());
        }

        private List<Metric> workloadCost() {
            ResourceUsage.Usage usage = Overhead.workload(resources, overheadInterval,
                    protocol.namespace(), protocol.deployment());
            return List.of(metric(MetricId.O3, "O3_cpu", usage.cpuMillicores()),
                    metric(MetricId.O3, "O3_memory", usage.memoryMiB()));
        }

        private RunRecord.StepRecord commit(int number) {
            List<RunRecord.StepRecord> commits = record.commits();
            return commits.size() > number ? commits.get(number) : null;
        }

        private RunRecord.StepRecord step(Scenario.Action action) {
            return record.steps().stream().filter(step -> step.action() == action).findFirst().orElse(null);
        }

        private List<Metric> underTheBadConfiguration() {
            RunRecord.StepRecord bad = commit(0);
            RunRecord.StepRecord fix = commit(1);
            if (bad == null || fix == null) {
                return List.of(metric(MetricId.E3, "E3_ratio", null), metric(MetricId.E3, "E3_outage", null));
            }
            Interval interval = new Interval(bad.startedAt(), fix.startedAt());
            return List.of(metric(MetricId.E3, "E3_ratio", availability.failedRatio(interval)),
                    metric(MetricId.E3, "E3_outage", availability.longestOutageMillis(interval)));
        }

        private Metric recovery() {
            List<Payload> payloads = scenario.payloads();
            RunRecord.StepRecord fix = commit(1);
            return InvalidConfiguration.recovery(observations, availability, afterBaseline,
                    payloads.size() > 1 ? payloads.get(1).configVersion() : null,
                    fix == null ? null : fix.t0());
        }

        private Long deliveredDuringOutage() {
            RunRecord.StepRecord infraUp = step(Scenario.Action.INFRA_UP);
            return infraUp == null || newVersion == null ? null
                    : Long.valueOf(InfrastructureOutage.deliveredDuringOutage(observations, newVersion,
                    infraUp.startedAt()));
        }

        private Metric replacementPod() {
            RunRecord.StepRecord deletePod = step(Scenario.Action.DELETE_POD);
            return deletePod == null ? new Metric(MetricId.U2, "U2", null, false, null)
                    : InfrastructureOutage.replacementPod(observations, deletePod.startedAt(), record.runEndAt());
        }
    }
}
