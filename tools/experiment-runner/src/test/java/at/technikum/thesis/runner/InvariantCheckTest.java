package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvariantCheckTest {

    private static final String IMAGE_ID =
            "docker.io/library/import-2026-11-07@sha256:8c1c747b8a86f64648490b4d8e611978129033afc9bce40f5565aba89a2e3932";

    private ObjectNode deployment;
    private ObjectNode service;
    private ObjectNode pods;
    private Protocol protocol;

    @BeforeEach
    void readFixtures() throws Exception {
        deployment = Fakes.resource("invariants/deployment.json");
        service = Fakes.resource("invariants/service.json");
        pods = Fakes.resource("invariants/pods.json");
        protocol = Fixtures.protocol();
    }

    @Test
    void a_deployment_service_and_pods_as_in_the_protocol_give_no_difference() {
        assertEquals(List.of(), differences(false));
    }

    @Test
    void two_replicas_instead_of_three_are_a_difference() {
        ((ObjectNode) deployment.path("spec")).put("replicas", 2);

        assertEquals(List.of("replicas is 2, the protocol says 3"), differences(false));
    }

    @Test
    void another_image_is_a_difference() {
        container().put("image", "probe-service:0.1.0");

        assertEquals(List.of("image is probe-service:0.1.0, the protocol says probe-service:0.2.0"),
                differences(false));
    }

    @Test
    void a_missing_workload_container_is_a_difference() {
        container().put("name", "other");

        assertEquals(List.of("container probe is missing"), differences(false));
    }

    @Test
    void another_cpu_request_is_a_difference() {
        ((ObjectNode) container().path("resources").path("requests")).put("cpu", "100m");

        assertEquals(List.of("cpu request is 100m, the protocol says 250m"), differences(false));
    }

    @Test
    void another_memory_request_is_a_difference() {
        ((ObjectNode) container().path("resources").path("requests")).put("memory", "256Mi");

        assertEquals(List.of("memory request is 256Mi, the protocol says 384Mi"), differences(false));
    }

    @Test
    void another_cpu_limit_is_a_difference() {
        ((ObjectNode) container().path("resources").path("limits")).put("cpu", "1");

        assertEquals(List.of("cpu limit is 1, the protocol says 500m"), differences(false));
    }

    @Test
    void another_memory_limit_is_a_difference() {
        ((ObjectNode) container().path("resources").path("limits")).put("memory", "1Gi");

        assertEquals(List.of("memory limit is 1Gi, the protocol says 512Mi"), differences(false));
    }

    @Test
    void a_missing_limit_is_a_difference() {
        ((ObjectNode) container().path("resources")).remove("limits");

        assertEquals(List.of("cpu limit is missing, the protocol says 500m",
                "memory limit is missing, the protocol says 512Mi"), differences(false));
    }

    @Test
    void another_readiness_path_is_a_difference() {
        ((ObjectNode) container().path("readinessProbe").path("httpGet")).put("path", "/actuator/health");

        assertEquals(List.of("readiness path is /actuator/health, the protocol says /actuator/health/readiness"),
                differences(false));
    }

    @Test
    void another_readiness_port_is_a_difference() {
        ((ObjectNode) container().path("readinessProbe").path("httpGet")).put("port", 8081);

        assertEquals(List.of("readiness port is 8081, the protocol says 8080"), differences(false));
    }

    @Test
    void another_readiness_period_is_a_difference() {
        ((ObjectNode) container().path("readinessProbe")).put("periodSeconds", 10);

        assertEquals(List.of("readiness period is 10, the protocol says 2"), differences(false));
    }

    @Test
    void another_topology_key_is_a_difference() {
        constraint().put("topologyKey", "topology.kubernetes.io/zone");

        assertEquals(List.of("topology key is topology.kubernetes.io/zone, the protocol says kubernetes.io/hostname"),
                differences(false));
    }

    @Test
    void another_max_skew_is_a_difference() {
        constraint().put("maxSkew", 2);

        assertEquals(List.of("topology max skew is 2, the protocol says 1"), differences(false));
    }

    @Test
    void another_when_unsatisfiable_is_a_difference() {
        constraint().put("whenUnsatisfiable", "ScheduleAnyway");

        assertEquals(List.of("topology whenUnsatisfiable is ScheduleAnyway, the protocol says DoNotSchedule"),
                differences(false));
    }

    @Test
    void a_second_topology_spread_constraint_is_a_difference() {
        ArrayNode constraints = (ArrayNode) deployment.path("spec").path("template").path("spec")
                .path("topologySpreadConstraints");
        constraints.add(constraints.get(0).deepCopy());

        assertEquals(List.of("number of topology spread constraints is 2, the protocol says 1"), differences(false));
    }

    @Test
    void another_service_type_is_a_difference() {
        ((ObjectNode) service.path("spec")).put("type", "ClusterIP");

        assertEquals(List.of("service type is ClusterIP, the protocol says NodePort"), differences(false));
    }

    @Test
    void another_service_port_is_a_difference() {
        port().put("port", 8080);

        assertEquals(List.of("service port is 8080, the protocol says 80"), differences(false));
    }

    @Test
    void another_service_target_port_is_a_difference() {
        port().put("targetPort", 9090);

        assertEquals(List.of("service target port is 9090, the protocol says 8080"), differences(false));
    }

    @Test
    void another_service_node_port_is_a_difference() {
        port().put("nodePort", 30081);

        assertEquals(List.of("service node port is 30081, the protocol says 30080"), differences(false));
    }

    @Test
    void another_service_selector_is_a_difference() {
        ((ObjectNode) service.path("spec").path("selector")).put("app", "other");

        assertEquals(List.of("service selector is {app=other}, the protocol says {app=probe-service}"),
                differences(false));
    }

    @Test
    void an_additional_selector_label_is_a_difference() {
        ((ObjectNode) service.path("spec").path("selector")).put("track", "canary");

        assertEquals(List.of("service selector is {app=probe-service, track=canary}, "
                + "the protocol says {app=probe-service}"), differences(false));
    }

    @Test
    void the_gate_variable_must_have_the_value_of_the_scenario() {
        assertEquals(List.of("CONFIG_GATE_ENABLED is false, the protocol says true"), differences(true));

        gate().put("value", "true");

        assertEquals(List.of(), differences(true));
        assertEquals(List.of("CONFIG_GATE_ENABLED is true, the protocol says false"), differences(false));
    }

    @Test
    void a_gate_variable_that_is_not_set_is_a_difference() {
        ArrayNode env = (ArrayNode) container().path("env");
        env.remove(env.size() - 1);

        assertEquals(List.of("CONFIG_GATE_ENABLED is missing, the protocol says false"), differences(false));
    }

    @Test
    void two_running_pods_instead_of_three_are_a_difference() {
        ((ArrayNode) pods.path("items")).remove(2);

        assertEquals(List.of("number of running pods is 2, the protocol says 3"), differences(false));
    }

    @Test
    void a_pod_that_is_not_running_does_not_count() {
        ((ObjectNode) pods.path("items").get(1).path("status")).put("phase", "Pending");

        assertEquals(List.of("number of running pods is 2, the protocol says 3"), differences(false));
    }

    @Test
    void four_running_pods_are_a_difference() {
        ObjectNode fourth = pods.path("items").get(0).deepCopy();
        ((ObjectNode) fourth.path("metadata")).put("name", "probe-service-f664c8f55-zzzzz");
        ((ObjectNode) fourth.path("spec")).put("nodeName", "thesis-worker4");
        ((ArrayNode) pods.path("items")).add(fourth);

        assertEquals(List.of("number of running pods is 4, the protocol says 3"), differences(false));
    }

    @Test
    void two_pods_on_one_node_are_a_difference() {
        ((ObjectNode) pods.path("items").get(1).path("spec")).put("nodeName", "thesis-worker3");

        assertEquals(List.of("more than one running pod is on node thesis-worker3"), differences(false));
    }

    @Test
    void pods_with_different_image_ids_are_a_difference() {
        ((ObjectNode) pods.path("items").get(2).path("status").path("containerStatuses").get(0))
                .put("imageID", "sha256:other");

        assertEquals(List.of("the running pods have different image ids: [" + IMAGE_ID + ", sha256:other]"),
                differences(false));
    }

    @Test
    void the_image_id_of_the_running_pods_is_reported_for_the_comparison_between_runs() {
        assertEquals(Set.of(IMAGE_ID), InvariantCheck.imageIds(pods, protocol));
    }

    @Test
    void fields_that_an_approach_may_vary_give_no_difference() {
        ObjectNode template = (ObjectNode) deployment.path("spec").path("template");
        ((ObjectNode) template.path("metadata").path("labels")).put("approach", "x");
        ((ObjectNode) deployment.path("metadata").path("annotations")).put("example.com/reload", "true");
        ((ArrayNode) container().path("env")).addObject().put("name", "DEMO_VALUE").put("value", "x");
        container().putObject("lifecycle").putObject("preStop").putObject("sleep").put("seconds", 5);
        ((ObjectNode) deployment.path("spec").path("strategy").path("rollingUpdate")).put("maxSurge", 1);
        ((ObjectNode) template.path("spec")).putArray("volumes");
        container().putArray("volumeMounts");

        assertEquals(List.of(), differences(false));
    }

    private List<String> differences(boolean gate) {
        return InvariantCheck.check(deployment, service, pods, protocol, gate);
    }

    private ObjectNode container() {
        return (ObjectNode) deployment.path("spec").path("template").path("spec").path("containers").get(0);
    }

    private ObjectNode gate() {
        ArrayNode env = (ArrayNode) container().path("env");
        return (ObjectNode) env.get(env.size() - 1);
    }

    private ObjectNode constraint() {
        return (ObjectNode) deployment.path("spec").path("template").path("spec")
                .path("topologySpreadConstraints").get(0);
    }

    private ObjectNode port() {
        return (ObjectNode) service.path("spec").path("ports").get(0);
    }
}
