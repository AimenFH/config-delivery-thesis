package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Campaign {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CampaignConfig.Loaded loaded;
    private final Function<ApproachDescriptor, DeliveryAdapter> adapters;
    private final Supplier<Sampler> samplers;
    private final RunJudge judge;
    private final Function<Path, RunStore> stores;
    private final CommandRunner commands;
    private final ServiceProbe probe;
    private final Pacer pacer;
    private final Path results;
    private final ClusterControl cluster;
    private final ObjectMapper json = new ObjectMapper();
    private String firstImageId;

    public Campaign(CampaignConfig.Loaded loaded, Supplier<Sampler> samplers, RunJudge judge,
                    Function<Path, RunStore> stores) {
        this(loaded, descriptor -> new CommandAdapter(descriptor, loaded.protocol(), CommandRunner.process()),
                samplers, judge, stores, CommandRunner.process(), ServiceProbe.http(loaded.protocol()),
                Pacer.real(), Path.of("results/raw"));
    }

    public Campaign(CampaignConfig.Loaded loaded,
                    Function<ApproachDescriptor, DeliveryAdapter> adapters,
                    Supplier<Sampler> samplers,
                    RunJudge judge,
                    Function<Path, RunStore> stores,
                    CommandRunner commands,
                    ServiceProbe probe,
                    Pacer pacer,
                    Path results) {
        this.loaded = loaded;
        this.adapters = adapters;
        this.samplers = samplers;
        this.judge = judge;
        this.stores = stores;
        this.commands = commands;
        this.probe = probe;
        this.pacer = pacer;
        this.results = results;
        this.cluster = new ClusterControl(loaded.protocol(), commands, probe, pacer);
    }

    public int run() throws Exception {
        CampaignConfig config = loaded.config();
        List<String> names = loaded.approaches().stream().map(ApproachDescriptor::name).toList();
        List<List<String>> blocks = BlockPlan.draw(names, config.maxBlocks(), config.seed());
        String campaignId = LocalDateTime.now().format(STAMP) + "_" + config.name();
        Path directory = results.resolve(campaignId);
        Files.createDirectories(directory.resolve("approaches"));
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("campaign.json").toFile(),
                new CampaignRecord(campaignId, config, loaded.protocol(), loaded.scenario(), names, blocks));
        Map<String, DeliveryAdapter> adapterOf = new LinkedHashMap<>();
        for (ApproachDescriptor descriptor : loaded.approaches()) {
            json.writerWithDefaultPrettyPrinter().writeValue(
                    directory.resolve("approaches").resolve(descriptor.name() + ".json").toFile(), descriptor);
            adapterOf.put(descriptor.name(), adapters.apply(descriptor));
        }
        Fingerprint.collect(loaded.protocol(), loaded.approaches(), commands, new RunnerClock())
                .write(directory.resolve("fingerprint.json"));
        RunStore store = stores.apply(directory);

        System.out.printf("Campaign %s: %d valid runs per approach, at most %d blocks, results in %s%n",
                campaignId, config.validRuns(), config.maxBlocks(), directory.toAbsolutePath());
        Map<String, Integer> valid = new LinkedHashMap<>();
        names.forEach(name -> valid.put(name, 0));
        try {
            for (int block = 1; block <= blocks.size() && !reached(valid, config.validRuns()); block++) {
                List<String> order = blocks.get(block - 1);
                System.out.printf("Block %d of at most %d: %s%n", block, blocks.size(), order);
                for (int position = 1; position <= order.size(); position++) {
                    String approach = order.get(position - 1);
                    for (int attempt = 1; attempt <= 1 + loaded.protocol().retriesPerBlock(); attempt++) {
                        RunMetrics metrics = runOnce(campaignId, store, adapterOf.get(approach),
                                block, position, attempt);
                        System.out.printf("  %s %s%s%n", metrics.runId(), metrics.verdict(),
                                metrics.invalidReasons() == null || metrics.invalidReasons().isEmpty()
                                        ? "" : " " + metrics.invalidReasons());
                        if (metrics.verdict() == RunMetrics.Verdict.VALID) {
                            valid.merge(approach, 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
        } catch (CampaignStopped stopped) {
            System.out.println("Campaign stopped: " + stopped.getMessage());
            return 1;
        }
        System.out.println("Valid runs: " + valid);
        return reached(valid, config.validRuns()) ? 0 : 2;
    }

    private static boolean reached(Map<String, Integer> valid, int validRuns) {
        return valid.values().stream().allMatch(count -> count >= validRuns);
    }

    private RunMetrics runOnce(String campaignId, RunStore store, DeliveryAdapter adapter,
                               int block, int position, int attempt) throws Exception {
        Protocol protocol = loaded.protocol();
        Scenario scenario = loaded.scenario();
        String runId = String.format("%s_%s_b%02d_a%d", LocalDateTime.now().format(STAMP), adapter.name(),
                block, attempt);
        RunnerClock preparation = new RunnerClock();

        must("deleting the Deployment", cluster.deleteDeployment(preparation));
        must("waiting until the pods are gone", cluster.waitUntilPodsGone(preparation));
        must("emptying the table " + protocol.database().table(), cluster.emptyTable(preparation));

        must("reset", adapter.reset(preparation));
        ChangeTrigger.Result sourceCheck = adapter.sourceCheck(preparation).orElse(null);

        for (ChangeTrigger.Result applied : adapter.deploy(preparation)) {
            must("deploy", applied);
        }
        must("the rollout", cluster.rolloutStatus(preparation));
        must("setting " + ClusterControl.GATE_VARIABLE, cluster.setGate(scenario.gate(), preparation));
        must("the rollout after setting " + ClusterControl.GATE_VARIABLE, cluster.rolloutStatus(preparation));

        pacer.sleepUntil(preparation, pacer.now(preparation) + protocol.settleSeconds() * 1000.0);

        checkInvariants(preparation);
        if (!cluster.awaitSteady(preparation)) {
            throw new CampaignStopped("the state is not steady after " + protocol.steadyTimeoutSeconds() + " s");
        }

        List<String> errors = new ArrayList<>();
        List<RunRecord.PodInfo> podsBefore = podList(preparation, "before", errors);
        List<RunRecord.InfraState> infrastructure = infrastructure(adapter, preparation, errors);

        RunnerClock clock = new RunnerClock();
        double preparationAtRunStart = pacer.now(preparation);
        Measurement measurement = new Measurement(samplers.get());
        StepExecutor executor = new StepExecutor(protocol, cluster, adapter, pacer, clock);
        StepExecutor.Timeline timeline;
        CampaignStopped restoreFailed = null;
        try {
            measurement.start(clock);
            timeline = executor.run(scenario, measurement::stop);
        } catch (CampaignStopped stopped) {
            if (executor.completed() == null) {
                throw stopped;
            }
            timeline = executor.completed();
            restoreFailed = stopped;
        } finally {
            measurement.stop();
        }

        List<RunRecord.PodInfo> podsAfter = podList(clock, "after", errors);
        JsonNode events = events(clock, errors);
        RunRecord record = new RunRecord(runId, campaignId, adapter.name(), scenario.name(),
                protocol.protocolVersion(), block, position, attempt, clock.startIso(), clock.startEpochMillis(),
                scenario.gate(), measurement.loadStartedAt, timeline.runEndAt(), measurement.scheduled,
                onRunClock(sourceCheck, preparationAtRunStart), timeline.steps(), measurement.observations,
                measurement.resources,
                new RunRecord.ClusterState(podsBefore, podsAfter, infrastructure, events), errors);
        RunMetrics metrics;
        try {
            metrics = judge.evaluate(record);
        } catch (RuntimeException e) {
            store.save(record, new RunMetrics(record.runId(), record.approach(), record.scenario(), record.block(),
                    RunMetrics.Verdict.INVALID, List.of("the evaluation failed: " + e), Set.of(), false, List.of()));
            throw new CampaignStopped("the evaluation of run " + runId + " failed: " + e);
        }
        store.save(record, metrics);
        if (restoreFailed != null) {
            throw restoreFailed;
        }
        return metrics;
    }

    private void checkInvariants(RunnerClock clock) {
        Protocol protocol = loaded.protocol();
        JsonNode pods;
        List<String> differences;
        try {
            JsonNode deployment = cluster.deploymentJson(clock);
            JsonNode service = cluster.serviceJson(clock);
            pods = cluster.workloadPodsJson(clock);
            differences = new ArrayList<>(InvariantCheck.check(deployment, service, pods, protocol,
                    loaded.scenario().gate()));
            differences.addAll(infrastructureDown(clock));
        } catch (ClusterControl.CommandFailed e) {
            throw new CampaignStopped("the invariants could not be read: " + e.getMessage());
        }
        Set<String> imageIds = InvariantCheck.imageIds(pods, protocol);
        String imageId = imageIds.size() == 1 ? imageIds.iterator().next() : null;
        if (firstImageId != null && imageId != null && !firstImageId.equals(imageId)) {
            differences.add("image id is " + imageId + ", the first run of the campaign had " + firstImageId);
        }
        if (!differences.isEmpty()) {
            throw new CampaignStopped("InvariantCheck found a difference: " + String.join("; ", differences));
        }
        if (firstImageId == null) {
            firstImageId = imageId;
        }
    }

    private List<String> infrastructureDown(RunnerClock clock) {
        Set<ApproachDescriptor.Infrastructure> entries = new LinkedHashSet<>();
        for (ApproachDescriptor approach : loaded.approaches()) {
            entries.addAll(approach.infrastructure());
        }
        List<String> differences = new ArrayList<>();
        for (ApproachDescriptor.Infrastructure entry : entries) {
            JsonNode deployment = cluster.deploymentJson(entry.namespace(), entry.deployment(), clock);
            int desired = deployment.path("spec").path("replicas").asInt(0);
            int ready = deployment.path("status").path("readyReplicas").asInt(0);
            String name = "infrastructure " + entry.namespace() + "/" + entry.deployment();
            if (desired < 1) {
                differences.add(name + " has " + desired + " desired replicas, at least 1 is required");
            } else if (ready < desired) {
                differences.add(name + " has " + ready + " of " + desired + " desired replicas ready");
            }
        }
        return differences;
    }

    private Set<String> namespaces() {
        Set<String> namespaces = new LinkedHashSet<>();
        namespaces.add(loaded.protocol().namespace());
        for (ApproachDescriptor approach : loaded.approaches()) {
            approach.infrastructure().forEach(entry -> namespaces.add(entry.namespace()));
        }
        return namespaces;
    }

    private List<RunRecord.PodInfo> podList(RunnerClock clock, String when, List<String> errors) {
        List<RunRecord.PodInfo> pods = new ArrayList<>();
        for (String namespace : namespaces()) {
            try {
                pods.addAll(cluster.pods(namespace, clock));
            } catch (ClusterControl.CommandFailed e) {
                errors.add("pod list of " + namespace + " " + when + " the run failed: " + e.getMessage());
            }
        }
        return pods;
    }

    private List<RunRecord.InfraState> infrastructure(DeliveryAdapter adapter, RunnerClock clock,
                                                      List<String> errors) {
        List<RunRecord.InfraState> states = new ArrayList<>();
        for (ApproachDescriptor.Infrastructure entry : adapter.infrastructure()) {
            try {
                states.add(cluster.infraState(entry.namespace(), entry.deployment(), clock));
            } catch (ClusterControl.CommandFailed e) {
                errors.add("state of " + entry.namespace() + "/" + entry.deployment() + " failed: "
                        + e.getMessage());
            }
        }
        return states;
    }

    private JsonNode events(RunnerClock clock, List<String> errors) {
        ObjectNode list = json.createObjectNode();
        list.put("apiVersion", "v1");
        list.put("kind", "List");
        ArrayNode items = list.putArray("items");
        for (String namespace : namespaces()) {
            try {
                cluster.eventsJson(namespace, clock).path("items").forEach(items::add);
            } catch (ClusterControl.CommandFailed e) {
                errors.add("events of " + namespace + " failed: " + e.getMessage());
            }
        }
        return list;
    }

    private static ChangeTrigger.Result onRunClock(ChangeTrigger.Result result, double preparationAtRunStart) {
        return result == null ? null : new ChangeTrigger.Result(result.command(),
                result.startedAt() - preparationAtRunStart, result.finishedAt() - preparationAtRunStart,
                result.exitCode(), result.output());
    }

    private static void must(String what, ChangeTrigger.Result result) {
        if (result.exitCode() != 0) {
            throw new CampaignStopped(what + " failed with exit code " + result.exitCode() + ": "
                    + result.command() + ": " + result.output());
        }
    }

    private final class Measurement {

        private final Sampler sampler;
        private ServiceProbe.Load load;
        private boolean sampling;
        private double loadStartedAt = Double.NaN;
        private long scheduled;
        private List<Observation> observations = List.of();
        private List<ResourceSample> resources = List.of();

        private Measurement(Sampler sampler) {
            this.sampler = sampler;
        }

        private void start(RunnerClock clock) {
            load = probe.startLoad(clock);
            loadStartedAt = load.startedAt();
            sampler.start(clock);
            sampling = true;
        }

        private void stop() {
            try {
                if (load != null) {
                    ServiceProbe.Load running = load;
                    load = null;
                    try {
                        observations = running.stop();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while the load was stopped", e);
                    }
                    scheduled = running.scheduledCount();
                }
            } finally {
                if (sampling) {
                    sampling = false;
                    resources = sampler.stop();
                }
            }
        }
    }
}
