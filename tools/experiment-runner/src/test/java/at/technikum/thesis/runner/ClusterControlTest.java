package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterControlTest {

    private final List<String> log = new ArrayList<>();
    private final Fakes.VirtualTime time = new Fakes.VirtualTime(log);
    private final Fakes.Kubectl kubectl = new Fakes.Kubectl(log, time);
    private final Fakes.Probe probe = new Fakes.Probe(log, time);
    private final RunnerClock clock = new RunnerClock();

    @Test
    void the_deployment_is_deleted_in_the_namespace_of_the_protocol() throws Exception {
        control().deleteDeployment(clock);

        assertEquals(List.of("$ kubectl -n thesis delete deployment probe-service --ignore-not-found --wait=true"),
                log);
    }

    @Test
    void waiting_for_the_pods_uses_the_service_selector_of_the_protocol() throws Exception {
        control().waitUntilPodsGone(clock);

        assertEquals(List.of("$ kubectl -n thesis wait --for=delete pod -l app=probe-service --timeout=120s"), log);
    }

    @Test
    void the_table_is_emptied_with_psql_in_the_database_pod_of_the_protocol() throws Exception {
        control().emptyTable(clock);

        assertEquals(List.of("$ kubectl -n data exec postgres-0 -c postgres -- psql -U thesis -d probe"
                + " -v ON_ERROR_STOP=1 -c do $$ begin if to_regclass('probe_request') is not null"
                + " then truncate table probe_request; end if; end $$"), log);
    }

    @Test
    void the_table_is_only_emptied_when_it_exists_because_flyway_creates_it_at_the_first_start() throws Exception {
        assertEquals("do $$ begin if to_regclass('visits') is not null then truncate table visits; end if; end $$",
                ClusterControl.emptyTableSql("visits"));
    }

    @Test
    void emptying_the_table_uses_the_database_fields_and_the_steady_timeout_of_the_protocol() throws Exception {
        Protocol other = Fixtures.protocolWith(tree -> {
            tree.put("steadyTimeoutSeconds", 45);
            ((ObjectNode) tree.get("database")).put("namespace", "db").put("pod", "pg-1").put("container", "pg")
                    .put("user", "lab").put("name", "labdb").put("table", "visits");
        });
        List<String> commands = new ArrayList<>();
        List<Long> timeouts = new ArrayList<>();
        CommandRunner recording = new CommandRunner() {
            @Override
            public ChangeTrigger.Result run(List<String> argv, RunnerClock usedClock) {
                commands.add(String.join(" ", argv));
                return new ChangeTrigger.Result(String.join(" ", argv), 0, 0, 0, "TRUNCATE TABLE");
            }

            @Override
            public ChangeTrigger.Result run(List<String> argv, RunnerClock usedClock, long timeoutMillis) {
                timeouts.add(timeoutMillis);
                return run(argv, usedClock);
            }
        };

        ChangeTrigger.Result result = new ClusterControl(other, recording, probe, time).emptyTable(clock);

        assertEquals(0, result.exitCode());
        assertEquals(List.of("kubectl -n db exec pg-1 -c pg -- psql -U lab -d labdb -v ON_ERROR_STOP=1"
                + " -c " + ClusterControl.emptyTableSql("visits")), commands);
        assertEquals(List.of(45_000L), timeouts);
    }

    @Test
    void the_rollout_of_the_workload_and_of_another_deployment_is_awaited() throws Exception {
        control().rolloutStatus(clock);
        control().rolloutStatus("platform", "rabbitmq", clock);

        assertEquals(List.of("$ kubectl -n thesis rollout status deployment/probe-service --timeout=120s",
                "$ kubectl -n platform rollout status deployment/rabbitmq --timeout=120s"), log);
    }

    @Test
    void the_gate_variable_is_set_to_true_or_false_with_set_env() throws Exception {
        control().setGate(true, clock);
        control().setGate(false, clock);

        assertEquals(List.of("$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=true",
                "$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=false"), log);
    }

    @Test
    void scale_and_delete_pod_name_their_target() throws Exception {
        control().scale("platform", "config-server", 0, clock);
        control().deletePod("probe-service-abc", clock);

        assertEquals(List.of("$ kubectl -n platform scale deployment/config-server --replicas=0",
                "$ kubectl -n thesis delete pod probe-service-abc --wait=false"), log);
    }

    @Test
    void deployment_service_pods_and_events_are_read_as_json() throws Exception {
        ClusterControl control = control();

        JsonNode deployment = control.deploymentJson(clock);
        JsonNode service = control.serviceJson(clock);
        JsonNode pods = control.workloadPodsJson(clock);
        JsonNode events = control.eventsJson("platform", clock);

        assertEquals(List.of("$ kubectl -n thesis get deployment probe-service -o json",
                "$ kubectl -n thesis get service probe-service -o json",
                "$ kubectl -n thesis get pods -l app=probe-service -o json",
                "$ kubectl -n platform get events -o json"), log);
        assertEquals(3, deployment.path("spec").path("replicas").asInt());
        assertEquals("NodePort", service.path("spec").path("type").asText());
        assertEquals(3, pods.path("items").size());
        assertEquals("event-of-platform", events.path("items").get(0).path("metadata").path("name").asText());
    }

    @Test
    void namespace_deployment_service_selector_and_timeout_come_from_the_protocol() throws Exception {
        Protocol other = Fixtures.protocolWith(tree -> {
            tree.put("namespace", "lab");
            tree.put("deployment", "echo");
            tree.put("service", "echo-svc");
            tree.put("steadyTimeoutSeconds", 45);
            ((ObjectNode) tree.get("workload")).putObject("serviceSelector").put("tier", "web").put("app", "echo");
        });
        ClusterControl control = new ClusterControl(other, kubectl, probe, time);
        kubectl.fails = command -> command.contains(" get ");

        control.deleteDeployment(clock);
        control.waitUntilPodsGone(clock);
        control.rolloutStatus(clock);
        control.setGate(true, clock);
        control.deletePod("echo-1", clock);
        assertThrows(ClusterControl.CommandFailed.class, () -> control.deploymentJson(clock));
        assertThrows(ClusterControl.CommandFailed.class, () -> control.serviceJson(clock));

        assertEquals(List.of("$ kubectl -n lab delete deployment echo --ignore-not-found --wait=true",
                "$ kubectl -n lab wait --for=delete pod -l app=echo,tier=web --timeout=45s",
                "$ kubectl -n lab rollout status deployment/echo --timeout=45s",
                "$ kubectl -n lab set env deployment/echo CONFIG_GATE_ENABLED=true",
                "$ kubectl -n lab delete pod echo-1 --wait=false",
                "$ kubectl -n lab get deployment echo -o json",
                "$ kubectl -n lab get service echo-svc -o json"), log);
    }

    @Test
    void a_failing_get_and_unreadable_output_are_reported_as_a_failed_command() throws Exception {
        ClusterControl control = control();
        kubectl.fails = command -> command.contains("get service");

        ClusterControl.CommandFailed failed = assertThrows(ClusterControl.CommandFailed.class,
                () -> control.serviceJson(clock));
        assertEquals(1, failed.exitCode());

        CommandRunner noJson = (argv, usedClock) -> new ChangeTrigger.Result(String.join(" ", argv), 0, 0, 0,
                "Unable to connect to the server");
        assertThrows(ClusterControl.CommandFailed.class,
                () -> new ClusterControl(Fixtures.protocol(), noJson, probe, time).deploymentJson(clock));
    }

    @Test
    void the_workload_pods_are_named_in_alphabetical_order() throws Exception {
        assertEquals(List.of("probe-service-f664c8f55-7hfd6", "probe-service-f664c8f55-jfsxk",
                "probe-service-f664c8f55-ldcdv"), control().workloadPodNames(clock));

        ((ArrayNode) kubectl.pods.path("items")).insert(0, kubectl.pods.path("items").get(2).deepCopy());

        assertEquals(List.of("probe-service-f664c8f55-7hfd6", "probe-service-f664c8f55-jfsxk",
                "probe-service-f664c8f55-ldcdv", "probe-service-f664c8f55-ldcdv"), control().workloadPodNames(clock));
    }

    @Test
    void the_ready_workload_pods_leave_out_a_pod_that_is_not_ready_and_a_pod_that_is_being_deleted()
            throws Exception {
        ArrayNode items = (ArrayNode) kubectl.pods.path("items");
        for (JsonNode condition : items.get(0).path("status").path("conditions")) {
            if (condition.path("type").asText().equals("Ready")) {
                ((ObjectNode) condition).put("status", "False");
            }
        }
        ((ObjectNode) items.get(1).path("metadata")).put("deletionTimestamp", "2026-11-21T10:00:00Z");

        assertEquals(List.of("probe-service-f664c8f55-ldcdv"), control().readyWorkloadPodNames(clock));
        assertEquals(3, control().workloadPodNames(clock).size());
    }

    @Test
    void a_pod_without_a_ready_condition_does_not_count_as_ready() throws Exception {
        ArrayNode items = (ArrayNode) kubectl.pods.path("items");
        ((ObjectNode) items.get(2).path("status")).remove("conditions");

        assertEquals(List.of("probe-service-f664c8f55-7hfd6", "probe-service-f664c8f55-jfsxk"),
                control().readyWorkloadPodNames(clock));
    }

    @Test
    void the_pod_list_holds_namespace_name_node_image_id_and_start_time() throws Exception {
        List<RunRecord.PodInfo> pods = control().pods("thesis", clock);

        assertEquals(List.of("$ kubectl -n thesis get pods -o json"), log);
        assertEquals(3, pods.size());
        assertEquals(new RunRecord.PodInfo("thesis", "probe-service-f664c8f55-7hfd6", "thesis-worker3",
                "docker.io/library/import-2026-11-07@sha256:"
                        + "8c1c747b8a86f64648490b4d8e611978129033afc9bce40f5565aba89a2e3932",
                "2026-11-08T16:49:49Z"), pods.get(0));
    }

    @Test
    void the_image_id_of_a_pod_without_the_workload_container_is_that_of_its_first_container() throws Exception {
        assertEquals(List.of(new RunRecord.PodInfo("platform", "infra-pod-1", "thesis-worker2", "sha256:infra",
                "2026-11-15T10:00:00Z")), control().pods("platform", clock));
    }

    @Test
    void the_state_of_an_infrastructure_deployment_is_its_replicas_and_requests() throws Exception {
        kubectl.replicas.put("platform/rabbitmq", 3);

        assertEquals(new RunRecord.InfraState("platform", "rabbitmq", 3, "250m", "384Mi"),
                control().infraState("platform", "rabbitmq", clock));
        assertEquals(3, control().replicas("platform", "rabbitmq", clock));
        assertEquals(List.of("$ kubectl -n platform get deployment rabbitmq -o json",
                "$ kubectl -n platform get deployment rabbitmq -o json"), log);
    }

    @Test
    void requests_of_several_containers_are_listed_and_missing_requests_are_null() throws Exception {
        JsonNode two = Fixtures.JSON.readTree("{\"spec\":{\"replicas\":2,\"template\":{\"spec\":{\"containers\":["
                + "{\"name\":\"a\",\"resources\":{\"requests\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"}}},"
                + "{\"name\":\"b\",\"resources\":{\"requests\":{\"cpu\":\"50m\"}}}]}}}}");
        JsonNode none = Fixtures.JSON.readTree("{\"spec\":{\"replicas\":1,\"template\":{\"spec\":{\"containers\":["
                + "{\"name\":\"a\"}]}}}}");

        assertEquals(new RunRecord.InfraState("ns", "d", 2, "100m+50m", "64Mi"),
                ClusterControl.infraState("ns", "d", two));
        assertEquals(new RunRecord.InfraState("ns", "d", 1, null, null), ClusterControl.infraState("ns", "d", none));
    }

    @Test
    void a_snapshot_is_the_number_of_steady_probes_of_the_protocol() throws Exception {
        probe.snapshots.add(List.of(
                new Observation(0, 100, 100, 100, 104, 200, "pod-a", "v1", 0, null),
                new Observation(1, 110, 110, 110, 173.5, 200, "pod-b", "v2", 0, null),
                Fakes.failed(2)));

        ClusterControl.Snapshot snapshot = control().snapshot(clock);

        assertEquals(List.of("probe 60"), log);
        assertEquals(new ClusterControl.Snapshot(Set.of("pod-a", "pod-b"), Set.of("v1", "v2"), 1, 63.5), snapshot);
    }

    @Test
    void steady_needs_three_pods_all_with_v1_no_failed_request_and_the_slowest_answer_under_200_ms()
            throws Exception {
        ClusterControl control = control();
        Set<String> three = Set.of("a", "b", "c");

        assertTrue(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1"), 0, 20)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(Set.of("a", "b"), Set.of("v1"), 0, 20)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(Set.of("a", "b", "c", "d"), Set.of("v1"), 0, 20)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1", "v2"), 0, 20)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v2"), 0, 20)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1"), 1, 20)));
    }

    @Test
    void an_answer_of_exactly_200_ms_is_not_under_the_limit() throws Exception {
        ClusterControl control = control();
        Set<String> three = Set.of("a", "b", "c");

        assertTrue(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1"), 0, 199.9)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1"), 0, 200)));
        assertFalse(control.isSteady(new ClusterControl.Snapshot(three, Set.of("v1"), 0, 450)));
    }

    @Test
    void steady_needs_the_same_pod_names_in_two_snapshots() throws Exception {
        ClusterControl control = control();
        ClusterControl.Snapshot first = new ClusterControl.Snapshot(Set.of("a", "b", "c"), Set.of("v1"), 0, 20);
        ClusterControl.Snapshot same = new ClusterControl.Snapshot(Set.of("c", "b", "a"), Set.of("v1"), 0, 30);
        ClusterControl.Snapshot replaced = new ClusterControl.Snapshot(Set.of("a", "b", "d"), Set.of("v1"), 0, 20);
        ClusterControl.Snapshot failing = new ClusterControl.Snapshot(Set.of("a", "b", "c"), Set.of("v1"), 2, 20);

        assertTrue(control.isSteady(first, same));
        assertFalse(control.isSteady(first, replaced));
        assertFalse(control.isSteady(first, failing));
        assertFalse(control.isSteady(failing, first));
    }

    @Test
    void the_two_snapshots_of_a_steady_state_are_10_s_apart() throws Exception {
        assertTrue(control().awaitSteady(clock));

        assertEquals(List.of("probe 60", "wait 10000 until 10000", "probe 60"), log);
    }

    @Test
    void the_check_is_repeated_until_two_snapshots_in_a_row_hold() throws Exception {
        probe.snapshots.add(Fakes.answers(60, List.of("pod-a", "pod-b"), "v1", 5));
        probe.snapshots.add(Fakes.answers(60, List.of("pod-a", "pod-b", "pod-x"), "v1", 5));

        assertTrue(control().awaitSteady(clock));

        assertEquals(List.of("probe 60", "wait 10000 until 10000", "probe 60", "wait 10000 until 20000", "probe 60",
                "wait 10000 until 30000", "probe 60"), log);
        assertEquals(30_000, time.now(clock));
    }

    @Test
    void a_state_that_becomes_steady_exactly_at_120_s_is_accepted() throws Exception {
        for (int i = 0; i < 11; i++) {
            probe.snapshots.add(Fakes.answers(60, Fakes.PODS, "v2", 5));
        }

        assertTrue(control().awaitSteady(clock));

        assertEquals(13, log.stream().filter("probe 60"::equals).count());
        assertEquals(120_000, time.now(clock));
    }

    @Test
    void a_state_that_is_not_steady_after_120_s_is_given_up() throws Exception {
        probe.otherwise = count -> Fakes.answers(count, Fakes.PODS, "v2", 5);

        assertFalse(control().awaitSteady(clock));

        assertEquals(13, log.stream().filter("probe 60"::equals).count());
        assertEquals(120_000, time.now(clock));
        assertEquals("wait 10000 until 120000", log.get(log.size() - 2));
    }

    private ClusterControl control() throws Exception {
        return new ClusterControl(Fixtures.protocol(), kubectl, probe, time);
    }
}
