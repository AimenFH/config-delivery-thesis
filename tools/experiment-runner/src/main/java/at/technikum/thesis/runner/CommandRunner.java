package at.technikum.thesis.runner;

import java.util.List;

@FunctionalInterface
public interface CommandRunner {

    ChangeTrigger.Result run(List<String> argv, RunnerClock clock);

    default ChangeTrigger.Result run(List<String> argv, RunnerClock clock, long timeoutMillis) {
        return run(argv, clock);
    }

    static CommandRunner process() {
        return new CommandRunner() {
            @Override
            public ChangeTrigger.Result run(List<String> argv, RunnerClock clock) {
                return new ChangeTrigger(argv, clock).fire();
            }

            @Override
            public ChangeTrigger.Result run(List<String> argv, RunnerClock clock, long timeoutMillis) {
                return new ChangeTrigger(argv, clock).fire(timeoutMillis);
            }
        };
    }
}
