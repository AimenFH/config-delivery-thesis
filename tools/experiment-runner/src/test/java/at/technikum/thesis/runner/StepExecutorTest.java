package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static at.technikum.thesis.runner.Scenario.Action.COMMIT;
import static at.technikum.thesis.runner.Scenario.Action.DELETE_POD;
import static at.technikum.thesis.runner.Scenario.Action.DELETE_READY_POD;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_DOWN;
import static at.technikum.thesis.runner.Scenario.Action.INFRA_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepExecutorTest {

    private static final List<ApproachDescriptor.Infrastructure> INFRASTRUCTURE = List.of(
            new ApproachDescriptor.Infrastructure("platform", "config-server"),
            new ApproachDescriptor.Infrastructure("platform", "rabbitmq"));
    private static final Payload V2 = new Payload("v2", "1000");
    private static final String SCALE_SERVER_DOWN = "$ kubectl -n platform scale deployment/config-server --replicas=0";
    private static final String SCALE_RABBIT_DOWN = "$ kubectl -n platform scale deployment/rabbitmq --replicas=0";
    private static final String SCALE_SERVER_BACK = "$ kubectl -n platform scale deployment/config-server --replicas=1";
    private static final String SCALE_RABBIT_BACK = "$ kubectl -n platform scale deployment/rabbitmq --replicas=3";
    private static final String READY_SERVER =
            "$ kubectl -n platform rollout status deployment/config-server --timeout=120s";
    private static final String READY_RABBIT = "$ kubectl -n platform rollout status deployment/rabbitmq --timeout=120s";
    private static final String GET_SERVER = "$ kubectl -n platform get deployment config-server -o json";
    private static final String GET_RABBIT = "$ kubectl -n platform get deployment rabbitmq -o json";
    private static final String GET_PODS = "$ kubectl -n thesis get pods -l app=probe-service -o json";
    private static final String DELETE_FIRST_POD =
            "$ kubectl -n thesis delete pod probe-service-f664c8f55-7hfd6 --wait=false";

    private final List<String> log = new ArrayList<>();
    private final Fakes.VirtualTime time = new Fakes.VirtualTime(log);
    private final Fakes.Kubectl kubectl = new Fakes.Kubectl(log, time);
    private final Fakes.Probe probe = new Fakes.Probe(log, time);
    private final Fakes.Adapter adapter = new Fakes.Adapter("approach", INFRASTRUCTURE, log, time);
    private final RunnerClock clock = new RunnerClock();
    private final Runnable stopMeasurement = () -> log.add("measurement stopped");

    StepExecutorTest() {
        kubectl.replicas.put("platform/config-server", 1);
        kubectl.replicas.put("platform/rabbitmq", 3);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void a_commit_is_followed_directly_by_the_trigger() throws Exception {
        executor().execute(0, new Scenario.Step(0, COMMIT, V2));

        assertEquals(List.of("approach commit v2 1000", "approach trigger v2 1000"), log);
    }

    @Test
    void the_step_record_of_a_commit_holds_its_start_end_exit_code_output_and_the_trigger_result() throws Exception {
        time.advance(clock, 90_000);

        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, COMMIT, V2));

        assertEquals(new RunRecord.StepRecord(0, 0, COMMIT, V2, 90_000, 90_120, 0, "output of commit v2 1000",
                new ChangeTrigger.Result("approach trigger v2 1000", 90_120, 90_160, 0, "output of trigger v2 1000"),
                null), record);
        assertEquals(90_120, record.t0());
    }

    @Test
    void a_failed_commit_runs_no_trigger() throws Exception {
        adapter.commitExitCode = payload -> 1;

        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, COMMIT, new Payload("v2", "abc")));

        assertEquals(List.of("approach commit v2 abc"), log);
        assertEquals(1, record.exitCode());
        assertNull(record.trigger());
        assertNull(record.t0());
    }

    @Test
    void an_approach_without_a_trigger_has_no_trigger_result() throws Exception {
        adapter.hasTrigger = false;

        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, COMMIT, V2));

        assertEquals(List.of("approach commit v2 1000"), log);
        assertEquals(0, record.exitCode());
        assertNull(record.trigger());
    }

    @Test
    void a_failed_trigger_stays_in_the_step_record() throws Exception {
        adapter.triggerExitCode = 7;

        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, COMMIT, V2));

        assertEquals(0, record.exitCode());
        assertEquals(7, record.trigger().exitCode());
    }

    @Test
    void infra_down_reads_the_replica_count_of_every_deployment_and_then_scales_it_to_0() throws Exception {
        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, INFRA_DOWN, null));

        assertEquals(List.of(GET_SERVER, SCALE_SERVER_DOWN, GET_RABBIT, SCALE_RABBIT_DOWN), log);
        assertEquals(Map.of("platform/config-server", 0, "platform/rabbitmq", 0), kubectl.replicas);
        assertEquals(0, record.exitCode());
        assertEquals("platform/config-server 1 -> 0, platform/rabbitmq 3 -> 0", record.detail());
    }

    @Test
    void infra_up_scales_each_deployment_back_to_the_remembered_count() throws Exception {
        StepExecutor executor = executor();
        executor.execute(0, new Scenario.Step(0, INFRA_DOWN, null));
        log.clear();

        RunRecord.StepRecord record = executor.execute(1, new Scenario.Step(120, INFRA_UP, null));

        assertEquals(List.of(SCALE_SERVER_BACK, SCALE_RABBIT_BACK), log);
        assertEquals(Map.of("platform/config-server", 1, "platform/rabbitmq", 3), kubectl.replicas);
        assertEquals(0, record.exitCode());
        assertEquals("platform/config-server 0 -> 1, platform/rabbitmq 0 -> 3", record.detail());
    }

    @Test
    void a_core_step_starts_before_its_first_command_and_ends_when_the_scale_calls_returned() throws Exception {
        kubectl.scaleMillis = 300;
        time.advance(clock, 90_000);
        StepExecutor executor = executor();

        RunRecord.StepRecord down = executor.execute(0, new Scenario.Step(0, INFRA_DOWN, null));
        time.advance(clock, 119_400);
        RunRecord.StepRecord up = executor.execute(3, new Scenario.Step(120, INFRA_UP, null));

        assertEquals(90_000, down.startedAt());
        assertEquals(90_600, down.finishedAt());
        assertEquals(210_000, up.startedAt());
        assertEquals(210_600, up.finishedAt());
        assertEquals(3, up.index());
        assertEquals(120, up.atSeconds());
        assertEquals(INFRA_UP, up.action());
    }

    @Test
    void delete_pod_deletes_the_first_pod_by_name_without_waiting() throws Exception {
        time.advance(clock, 150_000);

        RunRecord.StepRecord record = executor().execute(2, new Scenario.Step(60, DELETE_POD, null));

        assertEquals(List.of(GET_PODS, DELETE_FIRST_POD), log);
        assertEquals("probe-service-f664c8f55-7hfd6", record.detail());
        assertEquals(0, record.exitCode());
        assertEquals(150_000, record.startedAt());
        assertEquals(DELETE_POD, record.action());
    }

    @Test
    void delete_pod_deletes_the_alphabetically_first_pod_when_the_pod_list_is_not_sorted() throws Exception {
        ArrayNode items = (ArrayNode) kubectl.pods.get("items");
        items.add(items.remove(0));
        assertEquals(List.of("probe-service-f664c8f55-jfsxk", "probe-service-f664c8f55-ldcdv",
                "probe-service-f664c8f55-7hfd6"), names(items));

        RunRecord.StepRecord record = executor().execute(2, new Scenario.Step(60, DELETE_POD, null));

        assertEquals(List.of(GET_PODS, DELETE_FIRST_POD), log);
        assertEquals("probe-service-f664c8f55-7hfd6", record.detail());
        assertEquals(0, record.exitCode());
    }

    @Test
    void the_steps_start_after_warmup_and_baseline_at_their_offsets_and_the_run_ends_150_s_after_the_last_start()
            throws Exception {
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        StepExecutor.Timeline timeline = executor().run(scenario, stopMeasurement);

        assertEquals(List.of(
                "wait 90000 until 90000",
                GET_SERVER, SCALE_SERVER_DOWN, GET_RABBIT, SCALE_RABBIT_DOWN,
                "wait 30000 until 120000",
                "approach commit v2 1000", "approach trigger v2 1000",
                "wait 29840 until 150000",
                GET_PODS, DELETE_FIRST_POD,
                "wait 60000 until 210000",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK,
                "wait 150000 until 360000",
                "measurement stopped",
                READY_SERVER, READY_RABBIT), log);
        assertEquals(List.of(90_000.0, 120_000.0, 150_000.0, 210_000.0),
                timeline.steps().stream().map(RunRecord.StepRecord::startedAt).toList());
        assertEquals(List.of(INFRA_DOWN, COMMIT, DELETE_POD, INFRA_UP),
                timeline.steps().stream().map(RunRecord.StepRecord::action).toList());
        assertEquals(List.of(0, 1, 2, 3), timeline.steps().stream().map(RunRecord.StepRecord::index).toList());
        assertEquals(List.of(0, 30, 60, 120), timeline.steps().stream().map(RunRecord.StepRecord::atSeconds).toList());
        assertEquals(360_000, timeline.runEndAt());
    }

    @Test
    void a_step_never_starts_before_the_one_before_it_ended() throws Exception {
        adapter.commitMillis = 130_000;
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s2-invalid-config.json"));

        StepExecutor.Timeline timeline = executor().run(scenario, stopMeasurement);

        assertEquals(List.of(
                "wait 90000 until 90000",
                "approach commit v2 abc", "approach trigger v2 abc",
                "wait 0 until 210000",
                "approach commit v3 1000", "approach trigger v3 1000",
                "wait 19960 until 370040",
                "measurement stopped"), log);
        assertEquals(90_000, timeline.steps().get(0).startedAt());
        assertEquals(220_000, timeline.steps().get(0).finishedAt());
        assertEquals(220_040, timeline.steps().get(1).startedAt());
        assertEquals(370_040, timeline.runEndAt());
    }

    @Test
    void after_a_failed_commit_the_later_steps_still_run() throws Exception {
        adapter.commitExitCode = payload -> payload.configVersion().equals("v2") ? 1 : 0;
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s2-invalid-config.json"));

        StepExecutor.Timeline timeline = executor().run(scenario, stopMeasurement);

        assertEquals(List.of(
                "wait 90000 until 90000",
                "approach commit v2 abc",
                "wait 119880 until 210000",
                "approach commit v3 1000", "approach trigger v3 1000",
                "wait 149840 until 360000",
                "measurement stopped"), log);
        assertEquals(List.of(1, 0), timeline.steps().stream().map(RunRecord.StepRecord::exitCode).toList());
    }

    @Test
    void after_a_step_that_throws_the_restore_still_runs_and_waits_for_ready() throws Exception {
        kubectl.throwsOn = command -> command.contains("delete pod");
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        assertThrows(IllegalStateException.class, () -> executor().run(scenario, stopMeasurement));

        assertEquals(List.of(DELETE_FIRST_POD, "measurement stopped",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf(DELETE_FIRST_POD), log.size()));
        assertEquals(Map.of("platform/config-server", 1, "platform/rabbitmq", 3), kubectl.replicas);
    }

    @Test
    void after_a_commit_that_throws_the_restore_still_runs() throws Exception {
        adapter.commitThrows = true;
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        assertThrows(IllegalStateException.class, () -> executor().run(scenario, stopMeasurement));

        assertEquals(List.of("approach commit throws", "measurement stopped",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf("approach commit throws"), log.size()));
    }

    @Test
    void a_failed_core_step_stops_the_campaign_and_the_restore_runs() throws Exception {
        kubectl.fails = command -> command.contains("delete pod");
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        CampaignStopped stopped = assertThrows(CampaignStopped.class,
                () -> executor().run(scenario, stopMeasurement));

        assertTrue(stopped.getMessage().startsWith("step 2 DELETE_POD failed with exit code 1"),
                stopped.getMessage());
        assertEquals(List.of(DELETE_FIRST_POD, "measurement stopped",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf(DELETE_FIRST_POD), log.size()));
    }

    @Test
    void a_failed_infra_down_stops_the_campaign_and_restores_what_it_had_scaled() throws Exception {
        kubectl.fails = command -> command.equals(SCALE_RABBIT_DOWN.substring(2));
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        CampaignStopped stopped = assertThrows(CampaignStopped.class,
                () -> executor().run(scenario, stopMeasurement));

        assertTrue(stopped.getMessage().startsWith("step 0 INFRA_DOWN failed with exit code 1"),
                stopped.getMessage());
        assertEquals(List.of(SCALE_RABBIT_DOWN, "measurement stopped",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf(SCALE_RABBIT_DOWN), log.size()));
    }

    @Test
    void a_replica_count_that_cannot_be_read_fails_infra_down_before_anything_is_scaled() throws Exception {
        kubectl.fails = command -> command.equals(GET_SERVER.substring(2));

        RunRecord.StepRecord record = executor().execute(0, new Scenario.Step(0, INFRA_DOWN, null));

        assertEquals(1, record.exitCode());
        assertEquals(List.of(GET_SERVER), log);
    }

    @Test
    void a_failed_infra_up_stops_the_campaign_and_the_restore_tries_again() throws Exception {
        int[] calls = {0};
        kubectl.fails = command -> command.equals(SCALE_RABBIT_BACK.substring(2)) && calls[0]++ == 0;
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        CampaignStopped stopped = assertThrows(CampaignStopped.class,
                () -> executor().run(scenario, stopMeasurement));

        assertTrue(stopped.getMessage().startsWith("step 3 INFRA_UP failed with exit code 1"), stopped.getMessage());
        assertEquals(List.of(SCALE_SERVER_BACK, SCALE_RABBIT_BACK, "measurement stopped",
                SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf(SCALE_SERVER_BACK), log.size()));
        assertEquals(Map.of("platform/config-server", 1, "platform/rabbitmq", 3), kubectl.replicas);
    }

    @Test
    void delete_ready_pod_skips_a_pod_that_is_not_ready_and_deletes_the_first_ready_pod_by_name() throws Exception {
        notReady(0);
        time.advance(clock, 150_000);

        RunRecord.StepRecord record = executor().execute(1, new Scenario.Step(60, DELETE_READY_POD, null));

        assertEquals(List.of(GET_PODS, "$ kubectl -n thesis delete pod probe-service-f664c8f55-jfsxk --wait=false"),
                log);
        assertEquals("probe-service-f664c8f55-jfsxk", record.detail());
        assertEquals(0, record.exitCode());
        assertEquals(150_000, record.startedAt());
        assertEquals(DELETE_READY_POD, record.action());
    }

    @Test
    void delete_ready_pod_deletes_the_first_pod_by_name_when_no_pod_is_ready() throws Exception {
        notReady(0);
        notReady(1);
        notReady(2);

        RunRecord.StepRecord record = executor().execute(1, new Scenario.Step(60, DELETE_READY_POD, null));

        assertEquals(List.of(GET_PODS, GET_PODS, DELETE_FIRST_POD), log);
        assertEquals("probe-service-f664c8f55-7hfd6", record.detail());
        assertEquals(0, record.exitCode());
    }

    @Test
    void delete_ready_pod_fails_when_there_is_no_pod() throws Exception {
        kubectl.pods.putArray("items");

        RunRecord.StepRecord record = executor().execute(1, new Scenario.Step(60, DELETE_READY_POD, null));

        assertFalse(record.exitCode() == 0);
        assertNull(record.detail());
    }

    @Test
    void delete_pod_still_deletes_the_first_pod_by_name_when_that_pod_is_not_ready() throws Exception {
        notReady(0);

        RunRecord.StepRecord record = executor().execute(2, new Scenario.Step(60, DELETE_POD, null));

        assertEquals(List.of(GET_PODS, DELETE_FIRST_POD), log);
        assertEquals("probe-service-f664c8f55-7hfd6", record.detail());
    }

    private void notReady(int item) {
        for (JsonNode condition : kubectl.pods.path("items").get(item).path("status").path("conditions")) {
            if (condition.path("type").asText().equals("Ready")) {
                ((ObjectNode) condition).put("status", "False");
            }
        }
    }

    @Test
    void delete_pod_fails_when_there_is_no_pod() throws Exception {
        kubectl.pods.putArray("items");

        RunRecord.StepRecord record = executor().execute(2, new Scenario.Step(60, DELETE_POD, null));

        assertEquals(List.of(GET_PODS), log);
        assertFalse(record.exitCode() == 0);
        assertNull(record.detail());
    }

    @Test
    void an_interrupt_during_the_outage_still_restores_and_waits_for_ready() throws Exception {
        time.interruptWaitUntil = until -> until == 120_000;
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        assertThrows(InterruptedException.class, () -> executor().run(scenario, stopMeasurement));

        assertEquals(List.of("wait 30000 until 120000", "measurement stopped",
                SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf("wait 30000 until 120000"), log.size()));
    }

    @Test
    void an_interrupted_thread_keeps_its_interrupt_after_the_restore() throws Exception {
        kubectl.onCommand = command -> {
            if (command.contains("delete pod")) {
                Thread.currentThread().interrupt();
            }
        };
        List<Boolean> interruptedDuringRestore = new ArrayList<>();
        CommandRunner watching = (argv, usedClock) -> {
            if (argv.contains("rollout")) {
                interruptedDuringRestore.add(Thread.currentThread().isInterrupted());
            }
            return kubectl.run(argv, usedClock);
        };
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));
        time.interruptWaitUntil = until -> until == 210_000 && Thread.currentThread().isInterrupted();
        StepExecutor executor = new StepExecutor(Fixtures.protocol(),
                new ClusterControl(Fixtures.protocol(), watching, probe, time), adapter, time, clock);

        assertThrows(InterruptedException.class, () -> executor.run(scenario, stopMeasurement));

        assertEquals(List.of(false, false), interruptedDuringRestore);
        assertTrue(Thread.interrupted());
    }

    @Test
    void without_an_outage_the_end_of_a_run_scales_nothing_and_waits_for_nothing() throws Exception {
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s1-valid-change.json"));

        StepExecutor.Timeline timeline = executor().run(scenario, stopMeasurement);

        assertEquals(List.of("wait 90000 until 90000", "approach commit v2 1000", "approach trigger v2 1000",
                "wait 149840 until 240000", "measurement stopped"), log);
        assertEquals(240_000, timeline.runEndAt());
    }

    @Test
    void a_restore_that_fails_stops_the_campaign() throws Exception {
        kubectl.fails = command -> command.contains("rollout status deployment/rabbitmq");
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));

        CampaignStopped stopped = assertThrows(CampaignStopped.class,
                () -> executor().run(scenario, stopMeasurement));

        assertTrue(stopped.getMessage().startsWith("infrastructure was not restored"), stopped.getMessage());
        assertEquals(List.of(READY_SERVER, READY_RABBIT), log.subList(log.size() - 2, log.size()));
    }

    @Test
    void after_a_restore_that_fails_the_timeline_of_the_complete_run_is_still_available() throws Exception {
        kubectl.fails = command -> command.contains("rollout status deployment/rabbitmq");
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));
        StepExecutor executor = executor();

        assertThrows(CampaignStopped.class, () -> executor.run(scenario, stopMeasurement));

        assertEquals(List.of(INFRA_DOWN, COMMIT, DELETE_POD, INFRA_UP),
                executor.completed().steps().stream().map(RunRecord.StepRecord::action).toList());
        assertEquals(360_000, executor.completed().runEndAt());
    }

    @Test
    void after_a_failed_core_step_there_is_no_timeline_of_a_complete_run() throws Exception {
        kubectl.fails = command -> command.contains("delete pod");
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));
        StepExecutor executor = executor();

        assertThrows(CampaignStopped.class, () -> executor.run(scenario, stopMeasurement));

        assertNull(executor.completed());
    }

    @Test
    void a_complete_run_whose_measurement_could_not_be_stopped_has_no_timeline_of_a_complete_run()
            throws Exception {
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s1-valid-change.json"));
        StepExecutor executor = executor();

        assertThrows(IllegalArgumentException.class, () -> executor.run(scenario, () -> {
            throw new IllegalArgumentException("stop failed in the test");
        }));

        assertNull(executor.completed());
    }

    @Test
    void a_failing_stop_of_the_measurement_does_not_prevent_the_restore() throws Exception {
        Scenario scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));
        kubectl.throwsOn = command -> command.contains("delete pod");

        assertThrows(IllegalStateException.class, () -> executor().run(scenario, () -> {
            throw new IllegalArgumentException("stop failed in the test");
        }));

        assertEquals(List.of(DELETE_FIRST_POD, SCALE_SERVER_BACK, SCALE_RABBIT_BACK, READY_SERVER, READY_RABBIT),
                log.subList(log.indexOf(DELETE_FIRST_POD), log.size()));
    }

    private static List<String> names(ArrayNode items) {
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.path("metadata").path("name").asText()));
        return names;
    }

    private StepExecutor executor() throws Exception {
        Protocol protocol = Fixtures.protocol();
        return new StepExecutor(protocol, new ClusterControl(protocol, kubectl, probe, time), adapter, time, clock);
    }
}
