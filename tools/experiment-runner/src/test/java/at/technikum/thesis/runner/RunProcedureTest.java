package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static at.technikum.thesis.runner.Scenario.Action.COMMIT;
import static at.technikum.thesis.runner.Scenario.Action.DELETE_POD;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_DOWN;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunProcedureTest {

    private static final String DELETE = "$ kubectl -n thesis delete deployment probe-service --ignore-not-found --wait=true";
    private static final String PODS_GONE = "$ kubectl -n thesis wait --for=delete pod -l app=probe-service --timeout=120s";
    private static final String ROLLOUT = "$ kubectl -n thesis rollout status deployment/probe-service --timeout=120s";
    private static final String GATE_OFF = "$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=false";
    private static final String GATE_ON = "$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=true";
    private static final String GET_DEPLOYMENT = "$ kubectl -n thesis get deployment probe-service -o json";
    private static final String GET_SERVICE = "$ kubectl -n thesis get service probe-service -o json";
    private static final String GET_WORKLOAD_PODS = "$ kubectl -n thesis get pods -l app=probe-service -o json";
    private static final String PODS_THESIS = "$ kubectl -n thesis get pods -o json";
    private static final String PODS_PLATFORM = "$ kubectl -n platform get pods -o json";
    private static final String EVENTS_THESIS = "$ kubectl -n thesis get events -o json";
    private static final String EVENTS_PLATFORM = "$ kubectl -n platform get events -o json";
    private static final String GET_SERVER = "$ kubectl -n platform get deployment config-server -o json";
    private static final String GET_RABBIT = "$ kubectl -n platform get deployment rabbitmq -o json";
    private static final String IMAGE_ID =
            "docker.io/library/import-2026-11-07@sha256:8c1c747b8a86f64648490b4d8e611978129033afc9bce40f5565aba89a2e3932";

    @TempDir
    Path results;

    @Test
    void one_run_of_s1_follows_the_steps_1_to_9_of_the_plan_in_order() throws Exception {
        Fakes.World world = world("s1-valid-change", "way-c");

        assertEquals(0, world.campaign().run());

        assertEquals(List.of(
                DELETE,
                PODS_GONE,
                "way-c reset",
                "way-c sourceCheck",
                "way-c deploy",
                ROLLOUT,
                GATE_OFF,
                ROLLOUT,
                "wait 100000 until 100350",
                GET_DEPLOYMENT,
                GET_SERVICE,
                GET_WORKLOAD_PODS,
                "probe 60",
                "wait 10000 until 110350",
                "probe 60",
                PODS_THESIS,
                PODS_PLATFORM,
                GET_SERVER,
                GET_RABBIT,
                "load start",
                "sampler start",
                "wait 90000 until 90000",
                "way-c commit v2 1000",
                "way-c trigger v2 1000",
                "wait 149840 until 240000",
                "load stop",
                "sampler stop",
                PODS_THESIS,
                PODS_PLATFORM,
                EVENTS_THESIS,
                EVENTS_PLATFORM,
                "judge way-c",
                "store way-c"), world.logFrom(DELETE));
    }

    @Test
    void an_approach_without_source_check_and_trigger_runs_the_same_steps_without_them() throws Exception {
        Fakes.World world = world("s1-valid-change", "way-a");

        assertEquals(0, world.campaign().run());

        List<String> log = world.logFrom(DELETE);
        assertEquals(List.of(DELETE, PODS_GONE, "way-a reset", "way-a deploy", ROLLOUT, GATE_OFF, ROLLOUT,
                "wait 100000 until 100000"), log.subList(0, 8));
        assertEquals(List.of("wait 90000 until 90000", "way-a commit v2 1000", "wait 149880 until 240000",
                "load stop"), log.subList(log.indexOf("wait 90000 until 90000"), log.indexOf("load stop") + 1));
        assertNull(world.store.records.get(0).sourceCheck());
        assertNull(world.store.records.get(0).steps().get(0).trigger());
    }

    @Test
    void s2_sets_the_gate_on_and_commits_the_fix_120_s_after_the_bad_commit() throws Exception {
        Fakes.World world = world("s2-invalid-config", "way-b");

        assertEquals(0, world.campaign().run());

        List<String> log = world.logFrom(DELETE);
        assertEquals(List.of(DELETE, PODS_GONE, "way-b reset", "way-b deploy", ROLLOUT, GATE_ON, ROLLOUT),
                log.subList(0, 7));
        assertEquals(List.of(
                "load start",
                "sampler start",
                "wait 90000 until 90000",
                "way-b commit v2 abc",
                "wait 119880 until 210000",
                "way-b commit v3 1000",
                "wait 149880 until 360000",
                "load stop",
                "sampler stop"), log.subList(log.indexOf("load start"), log.indexOf("sampler stop") + 1));
        RunRecord record = world.store.records.get(0);
        assertTrue(record.gate());
        assertEquals(90_000, record.tCs());
        assertEquals(90_120, record.t0());
        assertEquals(210_000, record.steps().get(1).startedAt());
        assertEquals(210_120, record.steps().get(1).t0());
        assertEquals(new Payload("v3", "1000"), record.steps().get(1).payload());
        assertEquals(360_000, record.runEndAt());
    }

    @Test
    void s3_scales_down_commits_deletes_a_pod_scales_up_and_restores_before_the_cluster_is_recorded()
            throws Exception {
        Fakes.World world = world("s3-infra-outage", "way-c");
        world.kubectl.replicas.put("platform/rabbitmq", 3);

        assertEquals(0, world.campaign().run());

        List<String> log = world.logFrom(DELETE);
        assertEquals(List.of(
                "load start",
                "sampler start",
                "wait 90000 until 90000",
                GET_SERVER,
                "$ kubectl -n platform scale deployment/config-server --replicas=0",
                GET_RABBIT,
                "$ kubectl -n platform scale deployment/rabbitmq --replicas=0",
                "wait 30000 until 120000",
                "way-c commit v2 1000",
                "way-c trigger v2 1000",
                "wait 29840 until 150000",
                GET_WORKLOAD_PODS,
                "$ kubectl -n thesis delete pod probe-service-f664c8f55-7hfd6 --wait=false",
                "wait 60000 until 210000",
                "$ kubectl -n platform scale deployment/config-server --replicas=1",
                "$ kubectl -n platform scale deployment/rabbitmq --replicas=3",
                "wait 150000 until 360000",
                "load stop",
                "sampler stop",
                "$ kubectl -n platform rollout status deployment/config-server --timeout=120s",
                "$ kubectl -n platform rollout status deployment/rabbitmq --timeout=120s",
                PODS_THESIS,
                PODS_PLATFORM,
                EVENTS_THESIS,
                EVENTS_PLATFORM,
                "judge way-c",
                "store way-c"), log.subList(log.indexOf("load start"), log.size()));
    }

    @Test
    void the_run_record_holds_what_the_run_produced() throws Exception {
        Fakes.World world = world("s3-infra-outage", "way-c");
        world.kubectl.replicas.put("platform/rabbitmq", 3);
        world.probe.loadStartsAfterMillis = 2.5;

        assertEquals(0, world.campaign().run());

        RunRecord record = world.store.records.get(0);
        assertSame(record, world.judge.records.get(0));
        assertTrue(record.runId().matches("\\d{8}-\\d{6}_way-c_b01_a1"), record.runId());
        assertEquals(world.store.directory.getFileName().toString(), record.campaign());
        assertEquals("way-c", record.approach());
        assertEquals("s3-infra-outage", record.scenario());
        assertEquals(1, record.protocolVersion());
        assertEquals(1, record.block());
        assertEquals(1, record.positionInBlock());
        assertEquals(1, record.attempt());
        assertEquals(Instant.ofEpochMilli(record.runStartedAtEpochMillis()).toString(), record.runStartedAt());
        assertEquals(false, record.gate());
        assertEquals(2.5, record.loadStartedAt());
        assertEquals(360_000, record.runEndAt());
        assertEquals(12_000, record.scheduled());
        assertEquals(new ChangeTrigger.Result("way-c sourceCheck", -110_350, -110_000, 0, "output of sourceCheck"),
                record.sourceCheck());
        assertEquals(List.of(INFRA_DOWN, COMMIT, DELETE_POD, INFRA_UP),
                record.steps().stream().map(RunRecord.StepRecord::action).toList());
        assertEquals(List.of(90_000.0, 120_000.0, 150_000.0, 210_000.0),
                record.steps().stream().map(RunRecord.StepRecord::startedAt).toList());
        assertEquals(90_000, record.reference());
        assertEquals(120_000, record.tCs());
        assertEquals(120_120, record.t0());
        assertEquals("platform/config-server 1 -> 0, platform/rabbitmq 3 -> 0", record.steps().get(0).detail());
        assertEquals("probe-service-f664c8f55-7hfd6", record.steps().get(2).detail());
        assertEquals("platform/config-server 0 -> 1, platform/rabbitmq 0 -> 3", record.steps().get(3).detail());
        assertEquals(world.probe.loadObservations, record.observations());
        assertEquals(world.samplers.get(0).samples, record.resources());
        assertEquals(List.of(), record.errors());

        List<RunRecord.PodInfo> pods = List.of(
                new RunRecord.PodInfo("thesis", "probe-service-f664c8f55-7hfd6", "thesis-worker3", IMAGE_ID,
                        "2026-11-08T16:49:49Z"),
                new RunRecord.PodInfo("thesis", "probe-service-f664c8f55-jfsxk", "thesis-worker2", IMAGE_ID,
                        "2026-11-08T16:49:49Z"),
                new RunRecord.PodInfo("thesis", "probe-service-f664c8f55-ldcdv", "thesis-worker", IMAGE_ID,
                        "2026-11-08T16:49:49Z"),
                new RunRecord.PodInfo("platform", "infra-pod-1", "thesis-worker2", "sha256:infra",
                        "2026-11-15T10:00:00Z"));
        assertEquals(pods, record.cluster().podsBefore());
        assertEquals(pods, record.cluster().podsAfter());
        assertEquals(List.of(new RunRecord.InfraState("platform", "config-server", 1, "250m", "384Mi"),
                new RunRecord.InfraState("platform", "rabbitmq", 3, "250m", "384Mi")),
                record.cluster().infrastructure());
        assertEquals("List", record.cluster().events().path("kind").asText());
        assertEquals(List.of("event-of-thesis", "event-of-platform"),
                record.cluster().events().path("items").findValuesAsText("name"));
    }

    @Test
    void load_and_sampler_run_on_the_same_run_clock() throws Exception {
        Fakes.World world = world("s1-valid-change", "way-b");

        world.campaign().run();

        assertSame(world.probe.loadClock, world.samplers.get(0).clock);
        assertEquals(1, world.samplers.size());
    }

    @Test
    void every_run_gets_a_new_sampler_and_a_new_run_clock() throws Exception {
        Fakes.World world = new Fakes.World(Fakes.World.loaded("s1-valid-change", 1, 1, 2), results);

        world.campaign().run();

        assertEquals(3, world.samplers.size());
        assertTrue(world.samplers.get(0).clock != world.samplers.get(1).clock);
        assertTrue(world.samplers.get(1).clock != world.samplers.get(2).clock);
    }

    @Test
    void events_and_pods_are_read_from_the_infrastructure_namespaces_of_all_approaches() throws Exception {
        CampaignConfig.Loaded base = Fakes.World.loaded("s1-valid-change", 1, 1, 2);
        ApproachDescriptor wayA = base.approaches().get(0);
        ApproachDescriptor other = new ApproachDescriptor("other", "Other", wayA.manifests(), wayA.reset(),
                wayA.commit(), null, null, List.of(new ApproachDescriptor.Infrastructure("delivery", "operator")),
                wayA.declared());
        Fakes.World world = new Fakes.World(new CampaignConfig.Loaded(base.config(), base.protocol(),
                base.scenario(), List.of(wayA, other)), results);

        assertEquals(0, world.campaign().run());

        List<String> log = world.log;
        assertEquals(List.of(PODS_THESIS, PODS_PLATFORM, "$ kubectl -n delivery get pods -o json",
                EVENTS_THESIS, EVENTS_PLATFORM, "$ kubectl -n delivery get events -o json", "judge " + world.store
                        .records.get(1).approach()), log.subList(log.size() - 8, log.size() - 1));
        assertEquals(List.of("event-of-thesis", "event-of-platform", "event-of-delivery"),
                world.store.records.get(0).cluster().events().path("items").findValuesAsText("name"));
    }

    @Test
    void a_pod_list_or_event_list_that_fails_after_the_run_is_noted_and_the_run_is_still_saved() throws Exception {
        Fakes.World world = world("s1-valid-change", "way-b");
        world.kubectl.fails = command -> world.log.contains("load stop")
                && (command.equals(PODS_PLATFORM.substring(2)) || command.equals(EVENTS_THESIS.substring(2)));

        assertEquals(0, world.campaign().run());

        RunRecord record = world.store.records.get(0);
        assertEquals(2, record.errors().size());
        assertTrue(record.errors().get(0).startsWith("pod list of platform after the run failed"),
                record.errors().get(0));
        assertTrue(record.errors().get(1).startsWith("events of thesis failed"), record.errors().get(1));
        assertEquals(4, record.cluster().podsBefore().size());
        assertEquals(3, record.cluster().podsAfter().size());
        assertEquals(List.of("event-of-platform"),
                record.cluster().events().path("items").findValuesAsText("name"));
    }

    @Test
    void a_failing_source_check_is_stored_and_does_not_stop_the_run() throws Exception {
        Fakes.World world = world("s1-valid-change", "way-c");
        world.adapters.get("way-c").sourceCheckExitCode = 128;
        world.adapters.get("way-c").sourceCheckMillis = 2_000;

        assertEquals(0, world.campaign().run());

        assertEquals(new ChangeTrigger.Result("way-c sourceCheck", -112_000, -110_000, 128, "output of sourceCheck"),
                world.store.records.get(0).sourceCheck());
    }

    private Fakes.World world(String scenario, String approach) throws Exception {
        return new Fakes.World(Fakes.World.only(Fakes.World.loaded(scenario, 1, 1, 1), approach), results);
    }
}
