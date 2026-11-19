package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CommandAdapter implements DeliveryAdapter {

    private final ApproachDescriptor descriptor;
    private final Payload initial;
    private final CommandRunner runner;
    private final long triggerTimeoutMillis;

    public CommandAdapter(ApproachDescriptor descriptor, Protocol protocol) {
        this(descriptor, protocol, CommandRunner.process());
    }

    public CommandAdapter(ApproachDescriptor descriptor, Protocol protocol, CommandRunner runner) {
        this.descriptor = descriptor;
        this.initial = new Payload(protocol.initialVersion(), protocol.initialTimeoutMs());
        this.runner = runner;
        this.triggerTimeoutMillis = protocol.triggerTimeoutSeconds() * 1000L;
    }

    @Override
    public String name() {
        return descriptor.name();
    }

    @Override
    public List<ChangeTrigger.Result> deploy(RunnerClock clock) {
        List<ChangeTrigger.Result> results = new ArrayList<>();
        for (String entry : descriptor.manifests()) {
            ChangeTrigger.Result result = runner.run(List.of("kubectl", "apply", "-f", entry), clock);
            results.add(result);
            if (result.exitCode() != 0) {
                break;
            }
        }
        return results;
    }

    @Override
    public ChangeTrigger.Result reset(RunnerClock clock) {
        return runner.run(fill(descriptor.reset(), null), clock);
    }

    @Override
    public ChangeTrigger.Result commit(Payload payload, RunnerClock clock) {
        return runner.run(fill(descriptor.commit(), payload), clock);
    }

    @Override
    public Optional<ChangeTrigger.Result> trigger(Payload payload, RunnerClock clock) {
        if (descriptor.trigger() == null) {
            return Optional.empty();
        }
        return Optional.of(runner.run(fill(descriptor.trigger(), payload), clock, triggerTimeoutMillis));
    }

    @Override
    public Optional<ChangeTrigger.Result> sourceCheck(RunnerClock clock) {
        if (descriptor.sourceCheck() == null) {
            return Optional.empty();
        }
        return Optional.of(runner.run(fill(descriptor.sourceCheck(), null), clock));
    }

    @Override
    public List<ApproachDescriptor.Infrastructure> infrastructure() {
        return descriptor.infrastructure();
    }

    @Override
    public ApproachDescriptor.Declared declared() {
        return descriptor.declared();
    }

    private List<String> fill(List<String> argv, Payload payload) {
        return argv.stream().map(part -> {
            String filled = part
                    .replace("{initialVersion}", initial.configVersion())
                    .replace("{initialTimeoutMs}", initial.timeoutMs());
            if (payload != null) {
                filled = filled
                        .replace("{version}", payload.configVersion())
                        .replace("{timeoutMs}", payload.timeoutMs());
            }
            return filled;
        }).toList();
    }
}
