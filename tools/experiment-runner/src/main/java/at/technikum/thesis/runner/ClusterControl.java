package at.technikum.thesis.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class ClusterControl {

    public record Snapshot(Set<String> pods, Set<String> versions, int failed, double maxLatencyMillis) {
    }

    public static final class CommandFailed extends RuntimeException {

        private final int exitCode;

        public CommandFailed(String command, int exitCode, String reason) {
            super(command + " " + reason);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    public static final String GATE_VARIABLE = "CONFIG_GATE_ENABLED";

    private final Protocol protocol;
    private final CommandRunner commands;
    private final ServiceProbe probe;
    private final Pacer pacer;
    private final ObjectMapper json = new ObjectMapper();

    public ClusterControl(Protocol protocol, CommandRunner commands, ServiceProbe probe, Pacer pacer) {
        this.protocol = protocol;
        this.commands = commands;
        this.probe = probe;
        this.pacer = pacer;
    }

    public ChangeTrigger.Result deleteDeployment(RunnerClock clock) {
        return kubectl(clock, protocol.namespace(), "delete", "deployment", protocol.deployment(),
                "--ignore-not-found", "--wait=true");
    }

    public ChangeTrigger.Result waitUntilPodsGone(RunnerClock clock) {
        return kubectl(clock, protocol.namespace(), "wait", "--for=delete", "pod", "-l", selector(), timeout());
    }

    public ChangeTrigger.Result rolloutStatus(RunnerClock clock) {
        return rolloutStatus(protocol.namespace(), protocol.deployment(), clock);
    }

    public ChangeTrigger.Result rolloutStatus(String namespace, String deployment, RunnerClock clock) {
        return kubectl(clock, namespace, "rollout", "status", "deployment/" + deployment, timeout());
    }

    public ChangeTrigger.Result setGate(boolean enabled, RunnerClock clock) {
        return kubectl(clock, protocol.namespace(), "set", "env", "deployment/" + protocol.deployment(),
                GATE_VARIABLE + "=" + enabled);
    }

    public ChangeTrigger.Result scale(String namespace, String deployment, int replicas, RunnerClock clock) {
        return kubectl(clock, namespace, "scale", "deployment/" + deployment, "--replicas=" + replicas);
    }

    public ChangeTrigger.Result deletePod(String name, RunnerClock clock) {
        return kubectl(clock, protocol.namespace(), "delete", "pod", name, "--wait=false");
    }

    public JsonNode deploymentJson(RunnerClock clock) {
        return deploymentJson(protocol.namespace(), protocol.deployment(), clock);
    }

    public JsonNode deploymentJson(String namespace, String deployment, RunnerClock clock) {
        return getJson(clock, namespace, "deployment", deployment);
    }

    public JsonNode serviceJson(RunnerClock clock) {
        return getJson(clock, protocol.namespace(), "service", protocol.service());
    }

    public JsonNode workloadPodsJson(RunnerClock clock) {
        return getJson(clock, protocol.namespace(), "pods", "-l", selector());
    }

    public JsonNode podsJson(String namespace, RunnerClock clock) {
        return getJson(clock, namespace, "pods");
    }

    public JsonNode eventsJson(String namespace, RunnerClock clock) {
        return getJson(clock, namespace, "events");
    }

    public List<String> workloadPodNames(RunnerClock clock) {
        List<String> names = new ArrayList<>();
        for (JsonNode pod : workloadPodsJson(clock).path("items")) {
            names.add(pod.path("metadata").path("name").asText());
        }
        return names.stream().sorted().toList();
    }

    public List<RunRecord.PodInfo> pods(String namespace, RunnerClock clock) {
        return podInfos(podsJson(namespace, clock), protocol.workload().container());
    }

    public int replicas(String namespace, String deployment, RunnerClock clock) {
        List<String> argv = getCommand(namespace, "deployment", deployment);
        JsonNode replicas = getJson(clock, argv).path("spec").path("replicas");
        if (!replicas.isInt()) {
            throw new CommandFailed(String.join(" ", argv), 0, "gave no spec.replicas");
        }
        return replicas.asInt();
    }

    public RunRecord.InfraState infraState(String namespace, String deployment, RunnerClock clock) {
        return infraState(namespace, deployment, deploymentJson(namespace, deployment, clock));
    }

    public static RunRecord.InfraState infraState(String namespace, String deployment, JsonNode deploymentJson) {
        JsonNode containers = deploymentJson.path("spec").path("template").path("spec").path("containers");
        return new RunRecord.InfraState(namespace, deployment,
                deploymentJson.path("spec").path("replicas").asInt(),
                requests(containers, "cpu"), requests(containers, "memory"));
    }

    public static List<RunRecord.PodInfo> podInfos(JsonNode podList, String preferredContainer) {
        List<RunRecord.PodInfo> pods = new ArrayList<>();
        for (JsonNode pod : podList.path("items")) {
            pods.add(new RunRecord.PodInfo(
                    pod.path("metadata").path("namespace").asText(null),
                    pod.path("metadata").path("name").asText(null),
                    pod.path("spec").path("nodeName").asText(null),
                    imageId(pod, preferredContainer),
                    pod.path("status").path("startTime").asText(null)));
        }
        return pods;
    }

    public static String imageId(JsonNode pod, String preferredContainer) {
        JsonNode statuses = pod.path("status").path("containerStatuses");
        for (JsonNode status : statuses) {
            if (preferredContainer.equals(status.path("name").asText())) {
                return status.path("imageID").asText(null);
            }
        }
        return statuses.path(0).path("imageID").asText(null);
    }

    public Snapshot snapshot(RunnerClock clock) {
        Set<String> pods = new TreeSet<>();
        Set<String> versions = new TreeSet<>();
        int failed = 0;
        double maxLatency = 0;
        for (Observation o : probe.probe(protocol.steadyProbes(), clock)) {
            maxLatency = Math.max(maxLatency, o.latencyMillis());
            if (o.ok()) {
                pods.add(String.valueOf(o.pod()));
                versions.add(String.valueOf(o.configVersion()));
            } else {
                failed++;
            }
        }
        return new Snapshot(pods, versions, failed, maxLatency);
    }

    public boolean isSteady(Snapshot snapshot) {
        return snapshot.failed() == 0
                && snapshot.pods().size() == protocol.replicas()
                && snapshot.versions().equals(Set.of(protocol.initialVersion()))
                && snapshot.maxLatencyMillis() < protocol.steadyMaxLatencyMillis();
    }

    public boolean isSteady(Snapshot earlier, Snapshot later) {
        return isSteady(earlier) && isSteady(later) && earlier.pods().equals(later.pods());
    }

    public boolean awaitSteady(RunnerClock clock) throws InterruptedException {
        double deadline = pacer.now(clock) + protocol.steadyTimeoutSeconds() * 1000.0;
        Snapshot earlier = snapshot(clock);
        while (true) {
            double next = pacer.now(clock) + protocol.steadySnapshotGapSeconds() * 1000.0;
            if (next > deadline) {
                return false;
            }
            pacer.sleepUntil(clock, next);
            Snapshot later = snapshot(clock);
            if (isSteady(earlier, later)) {
                return true;
            }
            earlier = later;
        }
    }

    private JsonNode getJson(RunnerClock clock, String namespace, String... what) {
        return getJson(clock, getCommand(namespace, what));
    }

    private JsonNode getJson(RunnerClock clock, List<String> argv) {
        ChangeTrigger.Result result = commands.run(argv, clock);
        if (result.exitCode() != 0) {
            throw new CommandFailed(result.command(), result.exitCode(),
                    "failed with exit code " + result.exitCode() + ": " + result.output());
        }
        try {
            return json.readTree(result.output());
        } catch (JsonProcessingException e) {
            throw new CommandFailed(result.command(), 0, "gave no JSON");
        }
    }

    private static List<String> getCommand(String namespace, String... what) {
        List<String> argv = new ArrayList<>(List.of("kubectl", "-n", namespace, "get"));
        argv.addAll(List.of(what));
        argv.addAll(List.of("-o", "json"));
        return List.copyOf(argv);
    }

    private ChangeTrigger.Result kubectl(RunnerClock clock, String namespace, String... rest) {
        List<String> argv = new ArrayList<>(List.of("kubectl", "-n", namespace));
        argv.addAll(List.of(rest));
        return commands.run(List.copyOf(argv), clock);
    }

    public static String selector(Protocol protocol) {
        Map<String, String> sorted = new TreeMap<>(protocol.workload().serviceSelector());
        return sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private String selector() {
        return selector(protocol);
    }

    private String timeout() {
        return "--timeout=" + protocol.steadyTimeoutSeconds() + "s";
    }

    private static String requests(JsonNode containers, String resource) {
        List<String> values = new ArrayList<>();
        for (JsonNode container : containers) {
            JsonNode value = container.path("resources").path("requests").path(resource);
            if (value.isValueNode() && !value.isNull()) {
                values.add(value.asText());
            }
        }
        return values.isEmpty() ? null : String.join("+", values);
    }
}
