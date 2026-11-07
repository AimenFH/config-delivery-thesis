package at.technikum.thesis.runner;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ClusterControl {

    public record Snapshot(Set<String> pods, Set<String> versions, int failed, double maxLatencyMillis) {
    }

    private static final String NAMESPACE = "thesis";
    private static final String DEPLOYMENT = "probe-service";
    private static final double MAX_STEADY_LATENCY_MILLIS = 200;

    private final RunnerClock clock = new RunnerClock();

    public void switchTo(String manifests) {
        exec("kubectl", "-n", NAMESPACE, "delete", "deployment", DEPLOYMENT, "--ignore-not-found", "--wait=true");
        exec("kubectl", "apply", "-f", manifests);
        exec("kubectl", "-n", NAMESPACE, "rollout", "status", "deployment/" + DEPLOYMENT, "--timeout=300s");
    }

    public Snapshot snapshot(URI url, int requests) {
        ProbeClient client = new ProbeClient(url, clock);
        Set<String> pods = new TreeSet<>();
        Set<String> versions = new TreeSet<>();
        int failed = 0;
        double maxLatency = 0;
        for (int i = 0; i < requests; i++) {
            Observation o = client.probe(i, clock.millisSinceStart()).join();
            maxLatency = Math.max(maxLatency, o.latencyMillis());
            if (o.ok()) {
                pods.add(o.pod());
                versions.add(o.configVersion());
            } else {
                failed++;
            }
        }
        return new Snapshot(pods, versions, failed, maxLatency);
    }

    public Snapshot awaitSteady(URI url, int replicas, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Snapshot snapshot = snapshot(url, 60);
        while (!isSteady(snapshot, replicas) && System.nanoTime() < deadline) {
            Thread.sleep(2000);
            snapshot = snapshot(url, 60);
        }
        return snapshot;
    }

    public static boolean isSteady(Snapshot snapshot, int replicas) {
        return snapshot.failed() == 0 && snapshot.versions().size() == 1 && snapshot.pods().size() >= replicas
                && snapshot.maxLatencyMillis() < MAX_STEADY_LATENCY_MILLIS;
    }

    public static List<String> patchCommand(String versionKey, String version) {
        return List.of("kubectl", "-n", NAMESPACE, "patch", "configmap", "demo-config", "--type", "merge",
                "-p", "{\"data\":{\"" + versionKey + "\":\"" + version + "\"}}");
    }

    private void exec(String... command) {
        ChangeTrigger.Result result = new ChangeTrigger(List.of(command), clock).fire();
        System.out.println("  $ " + result.command());
        if (result.exitCode() != 0) {
            throw new IllegalStateException(result.command() + " failed with exit code "
                    + result.exitCode() + ": " + result.output());
        }
    }
}
