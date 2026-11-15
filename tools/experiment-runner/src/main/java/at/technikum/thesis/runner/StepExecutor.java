package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StepExecutor {

    public record Timeline(List<RunRecord.StepRecord> steps, double runEndAt) {
    }

    private final Protocol protocol;
    private final ClusterControl cluster;
    private final DeliveryAdapter adapter;
    private final Pacer pacer;
    private final RunnerClock clock;
    private final Map<ApproachDescriptor.Infrastructure, Integer> remembered = new LinkedHashMap<>();
    private final Set<ApproachDescriptor.Infrastructure> scaled = new LinkedHashSet<>();
    private Timeline completed;

    public StepExecutor(Protocol protocol, ClusterControl cluster, DeliveryAdapter adapter, Pacer pacer,
                        RunnerClock clock) {
        this.protocol = protocol;
        this.cluster = cluster;
        this.adapter = adapter;
        this.pacer = pacer;
        this.clock = clock;
    }

    public Timeline run(Scenario scenario, Runnable stopMeasurement) throws InterruptedException {
        Throwable failure = null;
        Timeline reached = null;
        try {
            List<RunRecord.StepRecord> records = new ArrayList<>();
            double firstStepAt = (protocol.warmupSeconds() + protocol.baselineSeconds()) * 1000.0;
            for (int index = 0; index < scenario.steps().size(); index++) {
                Scenario.Step step = scenario.steps().get(index);
                pacer.sleepUntil(clock, firstStepAt + step.atSeconds() * 1000.0);
                RunRecord.StepRecord record = execute(index, step);
                records.add(record);
                if (step.action() != Scenario.Action.COMMIT && record.exitCode() != 0) {
                    throw new CampaignStopped("step " + index + " " + step.action() + " failed with exit code "
                            + record.exitCode() + ": " + record.output());
                }
            }
            double runEndAt = records.get(records.size() - 1).startedAt() + protocol.observationSeconds() * 1000.0;
            pacer.sleepUntil(clock, runEndAt);
            reached = new Timeline(List.copyOf(records), runEndAt);
            return reached;
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    stopMeasurement.run();
                    completed = reached;
                } finally {
                    restore();
                }
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public Timeline completed() {
        return completed;
    }

    public RunRecord.StepRecord execute(int index, Scenario.Step step) {
        return switch (step.action()) {
            case COMMIT -> commit(index, step);
            case INFRA_DOWN -> infraDown(index, step);
            case INFRA_UP -> infraUp(index, step);
            case DELETE_POD -> deletePod(index, step);
        };
    }

    public void restore() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<ApproachDescriptor.Infrastructure, Integer> entry : List.copyOf(remembered.entrySet())) {
            ApproachDescriptor.Infrastructure target = entry.getKey();
            try {
                ChangeTrigger.Result result = cluster.scale(target.namespace(), target.deployment(),
                        entry.getValue(), clock);
                if (result.exitCode() == 0) {
                    remembered.remove(target);
                } else {
                    problems.add(result.command() + " failed with exit code " + result.exitCode()
                            + ": " + result.output());
                }
            } catch (RuntimeException e) {
                problems.add(name(target) + " could not be scaled back to " + entry.getValue() + ": " + e);
            }
        }
        for (ApproachDescriptor.Infrastructure target : List.copyOf(scaled)) {
            try {
                ChangeTrigger.Result result = cluster.rolloutStatus(target.namespace(), target.deployment(), clock);
                if (result.exitCode() == 0) {
                    scaled.remove(target);
                } else {
                    problems.add(result.command() + " failed with exit code " + result.exitCode()
                            + ": " + result.output());
                }
            } catch (RuntimeException e) {
                problems.add(name(target) + " did not report ready: " + e);
            }
        }
        if (!problems.isEmpty()) {
            throw new CampaignStopped("infrastructure was not restored: " + String.join("; ", problems));
        }
    }

    private RunRecord.StepRecord commit(int index, Scenario.Step step) {
        ChangeTrigger.Result commit = adapter.commit(step.payload(), clock);
        ChangeTrigger.Result trigger = commit.exitCode() == 0
                ? adapter.trigger(step.payload(), clock).orElse(null)
                : null;
        return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), step.payload(),
                commit.startedAt(), commit.finishedAt(), commit.exitCode(), commit.output(), trigger, null);
    }

    private RunRecord.StepRecord infraDown(int index, Scenario.Step step) {
        double startedAt = pacer.now(clock);
        List<String> detail = new ArrayList<>();
        List<String> output = new ArrayList<>();
        int exitCode = 0;
        for (ApproachDescriptor.Infrastructure target : adapter.infrastructure()) {
            int replicas;
            try {
                replicas = cluster.replicas(target.namespace(), target.deployment(), clock);
            } catch (ClusterControl.CommandFailed e) {
                exitCode = e.exitCode() == 0 ? -1 : e.exitCode();
                output.add(e.getMessage());
                break;
            }
            remembered.putIfAbsent(target, replicas);
            scaled.add(target);
            ChangeTrigger.Result result = cluster.scale(target.namespace(), target.deployment(), 0, clock);
            output.add(result.output());
            if (result.exitCode() != 0) {
                exitCode = result.exitCode();
                break;
            }
            detail.add(name(target) + " " + replicas + " -> 0");
        }
        return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), null, startedAt, pacer.now(clock),
                exitCode, String.join("\n", output), null, String.join(", ", detail));
    }

    private RunRecord.StepRecord infraUp(int index, Scenario.Step step) {
        double startedAt = pacer.now(clock);
        List<String> detail = new ArrayList<>();
        List<String> output = new ArrayList<>();
        int exitCode = 0;
        for (Map.Entry<ApproachDescriptor.Infrastructure, Integer> entry : List.copyOf(remembered.entrySet())) {
            ApproachDescriptor.Infrastructure target = entry.getKey();
            ChangeTrigger.Result result = cluster.scale(target.namespace(), target.deployment(),
                    entry.getValue(), clock);
            output.add(result.output());
            if (result.exitCode() != 0) {
                exitCode = result.exitCode();
                break;
            }
            remembered.remove(target);
            detail.add(name(target) + " 0 -> " + entry.getValue());
        }
        return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), null, startedAt, pacer.now(clock),
                exitCode, String.join("\n", output), null, String.join(", ", detail));
    }

    private RunRecord.StepRecord deletePod(int index, Scenario.Step step) {
        double startedAt = pacer.now(clock);
        List<String> names;
        try {
            names = cluster.workloadPodNames(clock);
        } catch (ClusterControl.CommandFailed e) {
            return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), null, startedAt,
                    pacer.now(clock), e.exitCode() == 0 ? -1 : e.exitCode(), e.getMessage(), null, null);
        }
        if (names.isEmpty()) {
            return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), null, startedAt,
                    pacer.now(clock), -1, "no pod to delete", null, null);
        }
        String first = names.get(0);
        ChangeTrigger.Result result = cluster.deletePod(first, clock);
        return new RunRecord.StepRecord(index, step.atSeconds(), step.action(), null, startedAt, pacer.now(clock),
                result.exitCode(), result.output(), null, first);
    }

    private static String name(ApproachDescriptor.Infrastructure target) {
        return target.namespace() + "/" + target.deployment();
    }
}
