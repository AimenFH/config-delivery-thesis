package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static at.technikum.thesis.runner.RunMetrics.Verdict.INVALID;
import static at.technikum.thesis.runner.RunMetrics.Verdict.VALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignTest {

    private static final String DELETE = "$ kubectl -n thesis delete deployment probe-service --ignore-not-found --wait=true";
    private static final List<String> NAMES = List.of("way-a", "way-b", "way-c", "way-d");
    private static final Set<String> ALL = Set.copyOf(NAMES);

    @TempDir
    Path results;

    @Test
    void a_valid_run_is_counted_and_the_campaign_ends_with_0_when_every_approach_has_its_valid_runs()
            throws Exception {
        Fakes.World world = world("s1-valid-change", 2, 5, 11);

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        assertEquals(8, world.store.records.size());
        assertEquals(List.of(1, 1, 1, 1, 2, 2, 2, 2), world.store.records.stream().map(RunRecord::block).toList());
        assertEquals(List.of(1, 2, 3, 4, 1, 2, 3, 4),
                world.store.records.stream().map(RunRecord::positionInBlock).toList());
        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1), world.store.records.stream().map(RunRecord::attempt).toList());
        assertEquals(ALL, approaches(world, 0, 4));
        assertEquals(ALL, approaches(world, 4, 8));
    }

    @Test
    void the_runs_follow_the_block_order_that_is_written_to_the_campaign_file() throws Exception {
        Fakes.World world = world("s1-valid-change", 3, 3, 20261115L);

        world.campaign().run();

        CampaignRecord written = campaignRecord();
        assertEquals(3, written.blocks().size());
        assertEquals(written.blocks().stream().flatMap(List::stream).toList(),
                world.store.records.stream().map(RunRecord::approach).toList());
    }

    @Test
    void two_campaigns_with_the_same_seed_run_in_the_same_order(@TempDir Path other) throws Exception {
        Fakes.World first = world("s1-valid-change", 3, 3, 77);
        Fakes.World second = new Fakes.World(Fakes.World.loaded("s1-valid-change", 3, 3, 77), other);

        first.campaign().run();
        second.campaign().run();

        assertEquals(12, first.store.records.size());
        assertEquals(first.store.records.stream().map(RunRecord::approach).toList(),
                second.store.records.stream().map(RunRecord::approach).toList());
    }

    @Test
    void an_invalid_run_is_repeated_at_once_with_the_same_approach() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 3);
        world.judge.verdict = run -> run.approach().equals("way-b") && run.attempt() == 1 ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        assertEquals(5, world.store.records.size());
        int first = indexOf(world, "way-b");
        assertEquals("way-b", world.store.records.get(first + 1).approach());
        assertEquals(1, world.store.records.get(first).attempt());
        assertEquals(2, world.store.records.get(first + 1).attempt());
        assertEquals(world.store.records.get(first).block(), world.store.records.get(first + 1).block());
        assertEquals(world.store.records.get(first).positionInBlock(),
                world.store.records.get(first + 1).positionInBlock());
        assertEquals(List.of(INVALID, VALID),
                world.store.metrics.subList(first, first + 2).stream().map(RunMetrics::verdict).toList());
    }

    @Test
    void an_invalid_run_is_kept_and_saved() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 3);
        world.judge.verdict = run -> run.approach().equals("way-a") && run.attempt() == 1 ? INVALID : VALID;

        world.campaign().run();

        assertEquals(1, world.store.metrics.stream().filter(metrics -> metrics.verdict() == INVALID).count());
        assertEquals(5, new HashSet<>(world.store.records.stream().map(RunRecord::runId).toList()).size());
    }

    @Test
    void an_invalid_run_is_repeated_at_most_twice_per_block_and_a_third_invalid_run_moves_on() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 3);
        world.judge.verdict = run -> run.approach().equals("way-b") ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(2, exitCode);
        assertEquals(6, world.store.records.size());
        int first = indexOf(world, "way-b");
        assertEquals(List.of("way-b", "way-b", "way-b"),
                world.store.records.subList(first, first + 3).stream().map(RunRecord::approach).toList());
        assertEquals(List.of(1, 2, 3),
                world.store.records.subList(first, first + 3).stream().map(RunRecord::attempt).toList());
        assertEquals(3, world.store.records.stream().filter(run -> run.approach().equals("way-b")).count());
        assertEquals(1, world.store.records.stream().filter(run -> run.approach().equals("way-a")).count());
        assertEquals(1, world.store.records.stream().filter(run -> run.approach().equals("way-c")).count());
        assertEquals(1, world.store.records.stream().filter(run -> run.approach().equals("way-d")).count());
    }

    @Test
    void the_second_repetition_can_still_make_the_block_valid() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 3);
        world.judge.verdict = run -> run.approach().equals("way-c") && run.attempt() < 3 ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        assertEquals(6, world.store.records.size());
    }

    @Test
    void the_retries_start_again_in_every_block() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 2, 3);
        world.judge.verdict = run -> run.approach().equals("way-b") ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(2, exitCode);
        assertEquals(12, world.store.records.size());
        assertEquals(List.of(1, 2, 3, 1, 2, 3), world.store.records.stream()
                .filter(run -> run.approach().equals("way-b")).map(RunRecord::attempt).toList());
        assertEquals(List.of(1, 1, 1, 2, 2, 2), world.store.records.stream()
                .filter(run -> run.approach().equals("way-b")).map(RunRecord::block).toList());
    }

    @Test
    void the_campaign_ends_with_2_when_an_approach_does_not_reach_its_valid_runs_within_the_blocks()
            throws Exception {
        Fakes.World world = world("s1-valid-change", 2, 2, 5);
        world.judge.verdict = run -> run.approach().equals("way-a") && run.block() == 2 ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(2, exitCode);
        assertEquals(10, world.store.records.size());
    }

    @Test
    void no_block_is_started_after_every_approach_reached_its_valid_runs() throws Exception {
        Fakes.World world = world("s1-valid-change", 2, 25, 5);

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        assertEquals(8, world.store.records.size());
        assertEquals(25, campaignRecord().blocks().size());
    }

    @Test
    void a_block_contains_every_approach_also_one_that_already_reached_its_number() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 3, 9);
        String firstOfSecondBlock = BlockPlan.draw(NAMES, 3, 9).get(1).get(0);
        world.judge.verdict = run -> run.approach().equals(firstOfSecondBlock) && run.block() == 1
                ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        assertEquals(10, world.store.records.size());
        assertEquals(firstOfSecondBlock, world.store.records.get(6).approach());
        assertEquals(ALL, approaches(world, 6, 10));
        assertEquals(List.of(2, 2, 2, 2), world.store.records.subList(6, 10).stream().map(RunRecord::block).toList());
    }

    @Test
    void at_most_the_blocks_of_the_campaign_file_are_run() throws Exception {
        Fakes.World world = world("s1-valid-change", 3, 4, 5);
        world.judge.verdict = run -> INVALID;

        int exitCode = world.campaign().run();

        assertEquals(2, exitCode);
        assertEquals(48, world.store.records.size());
        assertEquals(4, world.store.records.get(47).block());
    }

    @Test
    void campaign_file_descriptors_and_fingerprint_are_written_before_the_first_run() throws Exception {
        Fakes.World world = world("s2-invalid-config", 1, 2, 20261115L);
        List<List<String>> seenAtFirstRun = new ArrayList<>();
        world.kubectl.onCommand = command -> {
            if (("$ " + command).equals(DELETE) && seenAtFirstRun.isEmpty()) {
                seenAtFirstRun.add(files(campaignDirectory()));
            }
        };

        world.campaign().run();

        assertEquals(List.of("approaches/way-a.json", "approaches/way-b.json", "approaches/way-c.json",
                "approaches/way-d.json", "campaign.json", "fingerprint.json"), seenAtFirstRun.get(0));
        Path directory = campaignDirectory();
        assertTrue(directory.getFileName().toString().matches("\\d{8}-\\d{6}_test"), directory.toString());
        assertEquals(directory, world.store.directory);
        CampaignRecord written = campaignRecord();
        assertEquals(directory.getFileName().toString(), written.campaignId());
        assertEquals(world.loaded.config(), written.config());
        assertEquals(world.loaded.protocol(), written.protocol());
        assertEquals(world.loaded.scenario(), written.scenario());
        assertEquals(NAMES, written.approaches());
        assertEquals(BlockPlan.draw(NAMES, 2, 20261115L), written.blocks());
        for (ApproachDescriptor descriptor : world.loaded.approaches()) {
            assertEquals(descriptor, ApproachDescriptor.read(
                    directory.resolve("approaches").resolve(descriptor.name() + ".json")));
        }
        assertEquals(3, Fixtures.JSON.readValue(directory.resolve("fingerprint.json").toFile(), Fingerprint.class)
                .workloadImages().size());
        assertEquals(written.campaignId(), world.store.records.get(0).campaign());
    }

    @Test
    void a_fingerprint_command_that_fails_does_not_stop_the_campaign() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 1);
        world.kubectl.fails = command -> command.startsWith("git") || command.startsWith("docker")
                || command.startsWith("uname") || command.startsWith("java") || command.contains("version")
                || command.contains("get nodes");

        int exitCode = world.campaign().run();

        assertEquals(0, exitCode);
        Fingerprint fingerprint = Fixtures.JSON.readValue(campaignDirectory().resolve("fingerprint.json").toFile(),
                Fingerprint.class);
        assertEquals(new Fingerprint(null, null, null, null, null, fingerprint.workloadImages(),
                fingerprint.infrastructureImages(), null, null, null, null, null), fingerprint);
    }

    @Test
    void a_failed_reset_stops_the_campaign_with_1_and_keeps_what_was_written() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.adapters.values().forEach(adapter -> adapter.resetExitCode = 1);

        int exitCode = world.campaign().run();

        assertEquals(1, exitCode);
        assertEquals(0, world.store.records.size());
        assertEquals(1, world.log.stream().filter(DELETE::equals).count());
        assertFalse(world.log.stream().anyMatch(entry -> entry.endsWith(" deploy")));
        assertEquals(List.of("approaches/way-a.json", "approaches/way-b.json", "approaches/way-c.json",
                "approaches/way-d.json", "campaign.json", "fingerprint.json"), files(campaignDirectory()));
    }

    @Test
    void a_stop_in_a_later_run_keeps_the_runs_before_it() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        String third = BlockPlan.draw(NAMES, 1, 4).get(0).get(2);
        world.adapters.get(third).resetExitCode = 1;

        int exitCode = world.campaign().run();

        assertEquals(1, exitCode);
        assertEquals(2, world.store.records.size());
    }

    @Test
    void a_failed_deploy_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.adapters.values().forEach(adapter -> adapter.deployExitCode = 1);

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains("rollout status")));
    }

    @Test
    void a_failed_rollout_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("rollout status");

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains("set env")));
    }

    @Test
    void a_failed_rollout_after_the_gate_variable_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        int[] rollouts = {0};
        world.kubectl.fails = command -> command.contains("rollout status") && rollouts[0]++ == 1;

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertEquals(2, world.log.stream().filter(entry -> entry.contains("rollout status")).count());
        assertFalse(world.log.stream().anyMatch(entry -> entry.startsWith("wait ")));
    }

    @Test
    void a_gate_variable_that_cannot_be_set_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s2-invalid-config", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("set env");

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertEquals(1, world.log.stream().filter(entry -> entry.contains("rollout status")).count());
    }

    @Test
    void a_deployment_that_cannot_be_deleted_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("delete deployment");

        assertEquals(1, world.campaign().run());
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains("wait --for=delete")));
        assertFalse(world.log.stream().anyMatch(entry -> entry.endsWith(" reset")));
    }

    @Test
    void pods_that_do_not_go_away_stop_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("wait --for=delete");

        assertEquals(1, world.campaign().run());
        assertFalse(world.log.stream().anyMatch(entry -> entry.endsWith(" reset")));
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains("truncate table")));
    }

    @Test
    void a_table_that_cannot_be_emptied_stops_the_campaign_with_1_before_the_reset() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("truncate table probe_request");

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertFalse(world.log.stream().anyMatch(entry -> entry.endsWith(" reset")));
        assertFalse(world.log.stream().anyMatch(entry -> entry.equals("load start")));
    }

    @Test
    void the_gate_of_the_scenario_is_set_and_accepted_by_the_invariant_check() throws Exception {
        Fakes.World world = world("s2-invalid-config", 1, 1, 4);

        assertEquals(0, world.campaign().run());
        assertEquals(4, world.log.stream().filter(
                "$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=true"::equals).count());
        assertEquals(List.of(true, true, true, true), world.store.records.stream().map(RunRecord::gate).toList());
    }

    @Test
    void a_changed_replica_count_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        ((ObjectNode) world.kubectl.deployment.path("spec")).put("replicas", 2);

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertFalse(world.log.contains("probe 60"));
        assertFalse(world.log.contains("load start"));
    }

    @Test
    void a_gate_value_that_differs_from_the_scenario_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s2-invalid-config", 1, 1, 4);
        world.kubectl.setEnvApplies = false;

        assertEquals(1, world.campaign().run());
        assertTrue(world.log.contains("$ kubectl -n thesis set env deployment/probe-service CONFIG_GATE_ENABLED=true"));
        assertFalse(world.log.contains("load start"));
    }

    @Test
    void invariants_that_cannot_be_read_stop_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.contains("get service");

        assertEquals(1, world.campaign().run());
        assertFalse(world.log.contains("load start"));
    }

    @Test
    void another_image_id_in_a_later_run_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.judge.verdict = run -> {
            world.kubectl.pods.path("items").forEach(pod ->
                    ((ObjectNode) pod.path("status").path("containerStatuses").get(0)).put("imageID", "sha256:new"));
            return VALID;
        };

        assertEquals(1, world.campaign().run());
        assertEquals(1, world.store.records.size());
        assertEquals(2, world.log.stream().filter(DELETE::equals).count());
    }

    @Test
    void the_same_image_id_in_every_run_lets_the_campaign_continue() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);

        assertEquals(0, world.campaign().run());
        assertEquals(4, world.store.records.size());
    }

    @Test
    void a_state_that_is_not_steady_in_120_s_stops_the_campaign_with_1() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.probe.otherwise = count -> Fakes.answers(count, List.of("pod-a", "pod-b"), "v1", 5);

        assertEquals(1, world.campaign().run());
        assertEquals(13, world.log.stream().filter("probe 60"::equals).count());
        assertFalse(world.log.contains("load start"));
        assertEquals(0, world.store.records.size());
    }

    @Test
    void a_state_that_becomes_steady_in_time_is_measured() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.probe.snapshots.add(List.of(Fakes.failed(0)));
        world.probe.snapshots.add(Fakes.answers(60, Fakes.PODS, "v1", 250));

        assertEquals(0, world.campaign().run());
        assertEquals(4, world.store.records.size());
    }

    @Test
    void a_failed_core_step_stops_the_campaign_with_1_and_restores_the_infrastructure() throws Exception {
        Fakes.World world = world("s3-infra-outage", 1, 1, 4);
        world.kubectl.fails = command -> command.contains(" delete pod ");

        assertEquals(1, world.campaign().run());
        assertEquals(0, world.store.records.size());
        assertEquals(1, world.log.stream().filter("load start"::equals).count());
        assertEquals(1, world.log.stream().filter("load stop"::equals).count());
        assertEquals(1, world.log.stream().filter("sampler stop"::equals).count());
        assertTrue(world.kubectl.replicas.values().stream().allMatch(replicas -> replicas == 1),
                world.kubectl.replicas.toString());
        assertTrue(world.log.get(world.log.size() - 1).contains("rollout status deployment/"),
                world.log.get(world.log.size() - 1));
    }

    @Test
    void a_failed_commit_does_not_stop_the_campaign() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.adapters.values().forEach(adapter -> adapter.commitExitCode = payload -> 1);
        world.judge.verdict = run -> run.t0() == null ? INVALID : VALID;

        int exitCode = world.campaign().run();

        assertEquals(2, exitCode);
        assertEquals(12, world.store.records.size());
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains(" trigger ")));
    }

    @Test
    void the_infrastructure_is_back_before_the_next_run_starts() throws Exception {
        Fakes.World world = world("s3-infra-outage", 1, 1, 4);

        assertEquals(0, world.campaign().run());

        List<Integer> deletes = new ArrayList<>();
        List<Integer> lastReady = new ArrayList<>();
        for (int i = 0; i < world.log.size(); i++) {
            if (world.log.get(i).equals(DELETE)) {
                deletes.add(i);
            }
            if (world.log.get(i).startsWith("$ kubectl -n platform rollout status deployment/")) {
                lastReady.add(i);
            }
        }
        assertEquals(4, deletes.size());
        assertEquals(7, lastReady.size());
        assertTrue(world.log.subList(deletes.get(0), deletes.get(1)).stream()
                .anyMatch(entry -> entry.startsWith("$ kubectl -n platform rollout status deployment/")));
        assertTrue(world.log.subList(deletes.get(1), deletes.get(2)).stream()
                .anyMatch(entry -> entry.startsWith("$ kubectl -n platform rollout status deployment/")));
        assertTrue(world.log.subList(deletes.get(2), deletes.get(3)).stream()
                .anyMatch(entry -> entry.startsWith("$ kubectl -n platform rollout status deployment/")));
        assertTrue(world.kubectl.replicas.values().stream().allMatch(replicas -> replicas == 1));
    }

    @Test
    void with_every_infrastructure_deployment_up_the_deployments_of_all_approaches_are_read_before_the_load()
            throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);

        assertEquals(0, world.campaign().run());

        assertEquals(4, world.store.records.size());
        List<String> run = world.logFrom(DELETE);
        int invariants = run.indexOf("$ kubectl -n thesis get pods -l app=probe-service -o json");
        assertEquals(List.of(
                "$ kubectl -n platform get deployment reloader-reloader -o json",
                "$ kubectl -n platform get deployment config-watcher -o json",
                "$ kubectl -n platform get deployment config-server -o json",
                "$ kubectl -n platform get deployment rabbitmq -o json",
                "$ kubectl -n platform get deployment argocd-application-controller -o json",
                "$ kubectl -n platform get deployment argocd-repo-server -o json",
                "$ kubectl -n platform get deployment argocd-redis -o json",
                "probe 60"), run.subList(invariants + 1, invariants + 9));
        assertTrue(invariants < run.indexOf("load start"));
    }

    @Test
    void an_infrastructure_deployment_of_another_approach_at_0_replicas_stops_the_campaign_before_the_load()
            throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        String first = BlockPlan.draw(NAMES, 1, 4).get(0).get(0);
        String down = first.equals("way-b") ? "reloader-reloader" : "config-watcher";
        world.kubectl.replicas.put("platform/" + down, 0);

        assertEquals(1, world.campaign().run());

        assertEquals(0, world.store.records.size());
        assertEquals(1, world.log.stream().filter(DELETE::equals).count());
        assertTrue(world.log.contains("$ kubectl -n platform get deployment " + down + " -o json"));
        assertFalse(world.log.contains("probe 60"));
        assertFalse(world.log.contains("load start"));
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains(" scale ")));
    }

    @Test
    void an_infrastructure_deployment_with_a_replica_that_is_not_ready_stops_the_campaign_before_the_load()
            throws Exception {
        Fakes.World world = world("s3-infra-outage", 1, 1, 4);
        world.kubectl.replicas.put("platform/rabbitmq", 3);
        world.kubectl.readyReplicas.put("platform/rabbitmq", 2);

        assertEquals(1, world.campaign().run());

        assertEquals(0, world.store.records.size());
        assertEquals(1, world.log.stream().filter(DELETE::equals).count());
        assertFalse(world.log.contains("probe 60"));
        assertFalse(world.log.contains("load start"));
        assertFalse(world.log.stream().anyMatch(entry -> entry.contains(" scale ")));
    }

    @Test
    void an_infrastructure_deployment_that_cannot_be_read_stops_the_campaign_before_the_load() throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.kubectl.fails = command -> command.equals("kubectl -n platform get deployment config-server -o json")
                && world.log.contains("$ kubectl -n thesis get pods -l app=probe-service -o json");

        assertEquals(1, world.campaign().run());

        assertEquals(0, world.store.records.size());
        assertFalse(world.log.contains("load start"));
    }

    @Test
    void an_evaluation_that_throws_still_saves_the_raw_run_as_invalid_and_stops_the_campaign_with_1()
            throws Exception {
        Fakes.World world = world("s1-valid-change", 1, 1, 4);
        world.judge.verdict = run -> {
            throw new IllegalStateException("thrown in the test: evaluate");
        };

        assertEquals(1, world.campaign().run());

        assertEquals(1, world.store.records.size());
        RunRecord run = world.store.records.get(0);
        assertEquals(world.judge.records.get(0), run);
        assertEquals(world.probe.loadObservations, run.observations());
        RunMetrics metrics = world.store.metrics.get(0);
        assertEquals(run.runId(), metrics.runId());
        assertEquals(run.approach(), metrics.approach());
        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("the evaluation failed: java.lang.IllegalStateException: thrown in the test: evaluate"),
                metrics.invalidReasons());
        assertEquals(Set.of(), metrics.flags());
        assertEquals(List.of(), metrics.metrics());
        assertEquals(1, world.log.stream().filter(DELETE::equals).count());
        assertEquals("store " + run.approach(), world.log.get(world.log.size() - 1));
    }

    @Test
    void a_restore_that_fails_after_a_complete_run_still_judges_and_saves_that_run_and_stops_with_1()
            throws Exception {
        Fakes.World world = world("s3-infra-outage", 1, 1, 4);
        world.kubectl.fails = command -> command.startsWith("kubectl -n platform rollout status deployment/");

        assertEquals(1, world.campaign().run());

        assertEquals(1, world.store.records.size());
        assertEquals(1, world.judge.records.size());
        RunRecord run = world.store.records.get(0);
        assertEquals(List.of(Scenario.Action.INFRA_DOWN, Scenario.Action.COMMIT, Scenario.Action.DELETE_POD,
                Scenario.Action.INFRA_UP), run.steps().stream().map(RunRecord.StepRecord::action).toList());
        assertEquals(List.of(0, 0, 0, 0), run.steps().stream().map(RunRecord.StepRecord::exitCode).toList());
        assertEquals(360_000, run.runEndAt());
        assertEquals(world.probe.loadObservations, run.observations());
        assertEquals(VALID, world.store.metrics.get(0).verdict());
        assertEquals(1, world.log.stream().filter("load stop"::equals).count());
        assertTrue(world.log.indexOf("load stop") < world.log.indexOf("judge " + run.approach()));
        assertEquals(1, world.log.stream().filter(DELETE::equals).count());
        assertEquals("store " + run.approach(), world.log.get(world.log.size() - 1));
    }

    private Fakes.World world(String scenario, int validRuns, int maxBlocks, long seed) throws IOException {
        return new Fakes.World(Fakes.World.loaded(scenario, validRuns, maxBlocks, seed), results);
    }

    private static Set<String> approaches(Fakes.World world, int from, int to) {
        return new HashSet<>(world.store.records.subList(from, to).stream().map(RunRecord::approach).toList());
    }

    private static int indexOf(Fakes.World world, String approach) {
        for (int i = 0; i < world.store.records.size(); i++) {
            if (world.store.records.get(i).approach().equals(approach)) {
                return i;
            }
        }
        throw new AssertionError(approach + " did not run");
    }

    private Path campaignDirectory() {
        try (Stream<Path> children = Files.list(results)) {
            return children.findFirst().orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CampaignRecord campaignRecord() throws IOException {
        return Fixtures.JSON.readValue(campaignDirectory().resolve("campaign.json").toFile(), CampaignRecord.class);
    }

    private static List<String> files(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
