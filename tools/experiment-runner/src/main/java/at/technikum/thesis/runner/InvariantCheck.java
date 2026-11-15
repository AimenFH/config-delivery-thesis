package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class InvariantCheck {

    private InvariantCheck() {
    }

    public static List<String> check(JsonNode deployment, JsonNode service, JsonNode pods, Protocol protocol,
                                     boolean gate) {
        List<String> differences = new ArrayList<>();
        Protocol.Workload workload = protocol.workload();

        JsonNode spec = deployment.path("spec");
        same(differences, "replicas", String.valueOf(protocol.replicas()), spec.path("replicas"));

        JsonNode podSpec = spec.path("template").path("spec");
        JsonNode container = named(podSpec.path("containers"), workload.container());
        if (container == null) {
            differences.add("container " + workload.container() + " is missing");
        } else {
            same(differences, "image", workload.image(), container.path("image"));
            JsonNode resources = container.path("resources");
            same(differences, "cpu request", workload.cpuRequest(), resources.path("requests").path("cpu"));
            same(differences, "memory request", workload.memoryRequest(), resources.path("requests").path("memory"));
            same(differences, "cpu limit", workload.cpuLimit(), resources.path("limits").path("cpu"));
            same(differences, "memory limit", workload.memoryLimit(), resources.path("limits").path("memory"));
            JsonNode readiness = container.path("readinessProbe");
            same(differences, "readiness path", workload.readinessPath(), readiness.path("httpGet").path("path"));
            same(differences, "readiness port", String.valueOf(workload.readinessPort()),
                    readiness.path("httpGet").path("port"));
            same(differences, "readiness period", String.valueOf(workload.readinessPeriodSeconds()),
                    readiness.path("periodSeconds"));
            JsonNode variable = named(container.path("env"), ClusterControl.GATE_VARIABLE);
            same(differences, ClusterControl.GATE_VARIABLE, String.valueOf(gate),
                    variable == null ? null : variable.path("value").asText(null));
        }

        JsonNode constraints = podSpec.path("topologySpreadConstraints");
        same(differences, "number of topology spread constraints", "1", String.valueOf(constraints.size()));
        JsonNode constraint = constraints.path(0);
        same(differences, "topology key", workload.topologyKey(), constraint.path("topologyKey"));
        same(differences, "topology max skew", String.valueOf(workload.maxSkew()), constraint.path("maxSkew"));
        same(differences, "topology whenUnsatisfiable", workload.whenUnsatisfiable(),
                constraint.path("whenUnsatisfiable"));

        JsonNode serviceSpec = service.path("spec");
        same(differences, "service type", workload.serviceType(), serviceSpec.path("type"));
        JsonNode ports = serviceSpec.path("ports");
        same(differences, "number of service ports", "1", String.valueOf(ports.size()));
        JsonNode port = ports.path(0);
        same(differences, "service port", String.valueOf(workload.servicePort()), port.path("port"));
        same(differences, "service target port", String.valueOf(workload.serviceTargetPort()),
                port.path("targetPort"));
        same(differences, "service node port", String.valueOf(workload.serviceNodePort()), port.path("nodePort"));
        Map<String, String> selector = new TreeMap<>();
        serviceSpec.path("selector").fields()
                .forEachRemaining(entry -> selector.put(entry.getKey(), entry.getValue().asText()));
        Map<String, String> expectedSelector = new TreeMap<>(workload.serviceSelector());
        if (!selector.equals(expectedSelector)) {
            differences.add("service selector is " + selector + ", the protocol says " + expectedSelector);
        }

        List<JsonNode> running = running(pods);
        same(differences, "number of running pods", String.valueOf(protocol.replicas()),
                String.valueOf(running.size()));
        Set<String> nodes = new LinkedHashSet<>();
        for (JsonNode pod : running) {
            String node = pod.path("spec").path("nodeName").asText("");
            if (!nodes.add(node)) {
                differences.add("more than one running pod is on node " + node);
            }
        }
        Set<String> imageIds = imageIds(pods, protocol);
        if (imageIds.size() > 1) {
            differences.add("the running pods have different image ids: " + imageIds);
        }
        return differences;
    }

    public static Set<String> imageIds(JsonNode pods, Protocol protocol) {
        Set<String> imageIds = new LinkedHashSet<>();
        for (JsonNode pod : running(pods)) {
            JsonNode status = named(pod.path("status").path("containerStatuses"), protocol.workload().container());
            imageIds.add(status == null ? "none" : status.path("imageID").asText("none"));
        }
        return imageIds;
    }

    private static List<JsonNode> running(JsonNode pods) {
        List<JsonNode> running = new ArrayList<>();
        for (JsonNode pod : pods.path("items")) {
            if ("Running".equals(pod.path("status").path("phase").asText())) {
                running.add(pod);
            }
        }
        return running;
    }

    private static JsonNode named(JsonNode list, String name) {
        for (JsonNode entry : list) {
            if (name.equals(entry.path("name").asText(null))) {
                return entry;
            }
        }
        return null;
    }

    private static void same(List<String> differences, String field, String expected, JsonNode actual) {
        same(differences, field, expected,
                actual == null || actual.isMissingNode() || actual.isNull() ? null : actual.asText());
    }

    private static void same(List<String> differences, String field, String expected, String actual) {
        if (!expected.equals(actual)) {
            differences.add(field + " is " + (actual == null ? "missing" : actual) + ", the protocol says " + expected);
        }
    }
}
