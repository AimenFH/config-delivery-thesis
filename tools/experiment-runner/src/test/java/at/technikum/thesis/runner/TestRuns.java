package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

final class TestRuns {

    static final double PERIOD = 20;
    static final String A = "probe-service-a";
    static final String B = "probe-service-b";
    static final String C = "probe-service-c";
    static final List<String> PODS = List.of(A, B, C);
    static final String NODE = "thesis-worker";
    static final String INFRA_POD = "reloader-reloader-7d9f-x1";
    static final long MIB = 1024 * 1024;

    interface VersionAt {
        String of(String pod, double intendedAt);
    }

    private TestRuns() {
    }

    static Observation answer(long seq, String pod, String version) {
        return answer(seq, pod, version, 2);
    }

    static Observation answer(long seq, String pod, String version, double latency) {
        double at = seq * PERIOD;
        return new Observation(seq, at, at, at, at + latency, 200, pod, version, 0, null);
    }

    static Observation seen(double intendedAt, double receivedAt, String pod, String version) {
        return new Observation(Math.round(intendedAt / PERIOD), intendedAt, intendedAt, intendedAt, receivedAt,
                200, pod, version, 0, null);
    }

    static Observation failure(long seq) {
        double at = seq * PERIOD;
        return new Observation(seq, at, at, at, at + 2000, 0, null, null, 0,
                "HttpTimeoutException: request timed out");
    }

    static Observation late(long seq, String pod, String version, double lag) {
        double at = seq * PERIOD;
        return new Observation(seq, at, at + lag, at + lag, at + lag + 2, 200, pod, version, 0, null);
    }

    static String podOf(long seq) {
        return PODS.get((int) (seq % 3));
    }

    static List<Observation> load(long fromSeq, long toSeq, VersionAt version) {
        List<Observation> observations = new ArrayList<>();
        for (long seq = fromSeq; seq < toSeq; seq++) {
            String pod = podOf(seq);
            observations.add(answer(seq, pod, version.of(pod, seq * PERIOD)));
        }
        return observations;
    }

    static VersionAt allSwitchAt(double at, String from, String to) {
        return (pod, intendedAt) -> intendedAt < at ? from : to;
    }

    static List<Observation> replace(List<Observation> all, Observation... replacements) {
        List<Observation> changed = new ArrayList<>(all);
        for (Observation replacement : replacements) {
            changed.replaceAll(o -> o.seq() == replacement.seq() ? replacement : o);
        }
        return changed;
    }

    static List<Observation> without(List<Observation> all, LongPredicate removed) {
        return all.stream().filter(o -> !removed.test(o.seq())).toList();
    }

    static List<Observation> change(List<Observation> all, LongPredicate selected, UnaryOperator<Observation> change) {
        return all.stream().map(o -> selected.test(o.seq()) ? change.apply(o) : o).toList();
    }

    static Observation onPod(Observation o, String pod) {
        return new Observation(o.seq(), o.intendedAt(), o.scheduledAt(), o.sentAt(), o.receivedAt(),
                o.status(), pod, o.configVersion(), o.startedAt(), o.error());
    }

    static Observation withVersion(Observation o, String version) {
        return new Observation(o.seq(), o.intendedAt(), o.scheduledAt(), o.sentAt(), o.receivedAt(),
                o.status(), o.pod(), version, o.startedAt(), o.error());
    }

    static List<Observation> reroute(List<Observation> all, long fromSeq, long toSeq, String fromPod, String toPod) {
        return change(all, seq -> seq >= fromSeq && seq < toSeq,
                o -> fromPod.equals(o.pod()) ? onPod(o, toPod) : o);
    }

    static List<ResourceSample> samples(double from, double to) {
        List<ResourceSample> samples = new ArrayList<>();
        int round = 0;
        for (double at = from; at <= to; at += 5_000) {
            samples.add(new ResourceSample(at, NODE, "platform", INFRA_POD, "reloader",
                    100 + round * 0.05, 64 * MIB, 0));
            for (String pod : PODS) {
                samples.add(new ResourceSample(at, NODE, "thesis", pod, "probe",
                        20 + round * 0.25, 256 * MIB, 0));
            }
            round++;
        }
        return samples;
    }

    static RunRecord.StepRecord commit(int index, int atSeconds, Payload payload, double startedAt, double finishedAt,
                                       int exitCode, ChangeTrigger.Result trigger) {
        return new RunRecord.StepRecord(index, atSeconds, Scenario.Action.COMMIT, payload, startedAt, finishedAt,
                exitCode, "", trigger, null);
    }

    static RunRecord.StepRecord commit(int index, int atSeconds, Payload payload, double startedAt, double finishedAt) {
        return commit(index, atSeconds, payload, startedAt, finishedAt, 0, null);
    }

    static RunRecord.StepRecord step(int index, int atSeconds, Scenario.Action action, double startedAt,
                                     double finishedAt) {
        return new RunRecord.StepRecord(index, atSeconds, action, null, startedAt, finishedAt, 0, "", null, null);
    }

    static RunRecord run(String runId, String approach, String scenario, int block, int position, int attempt,
                         List<RunRecord.StepRecord> steps, List<Observation> observations,
                         List<ResourceSample> resources, double runEndAt) {
        List<RunRecord.PodInfo> pods = PODS.stream()
                .map(pod -> new RunRecord.PodInfo("thesis", pod, NODE, "sha256:1234", "2026-11-15T20:00:00Z"))
                .toList();
        RunRecord.ClusterState cluster = new RunRecord.ClusterState(pods, pods, List.of(
                new RunRecord.InfraState("platform", "reloader-reloader", 1, "10m", "64Mi"),
                new RunRecord.InfraState("platform", "config-watcher", 1, "50m", "128Mi"),
                new RunRecord.InfraState("platform", "config-server", 1, "100m", "256Mi"),
                new RunRecord.InfraState("platform", "rabbitmq", 1, null, null)),
                Fixtures.JSON.createObjectNode().put("kind", "List"));
        return new RunRecord(runId, "20261115-200000_test", approach, scenario, 1, block, position, attempt,
                "2026-11-15T19:00:00Z", 1794769200000L, false, 0, runEndAt, (long) (runEndAt / PERIOD),
                null, steps, observations, resources, cluster, List.of());
    }

    static RunRecord s1(List<Observation> observations) {
        return s1(observations, samples(0, 240_000));
    }

    static RunRecord s1(List<Observation> observations, List<ResourceSample> resources) {
        return s1("run-1", "way-a", List.of(commit(0, 0, new Payload("v2", "1000"), 90_000, 90_100)),
                observations, resources);
    }

    static RunRecord s1(String runId, String approach, List<RunRecord.StepRecord> steps,
                        List<Observation> observations, List<ResourceSample> resources) {
        return run(runId, approach, "s1-valid-change", 1, 1, 1, steps, observations, resources, 240_000);
    }

    static List<Observation> cleanS1() {
        return load(0, 12_000, allSwitchAt(95_000, "v1", "v2"));
    }
}
