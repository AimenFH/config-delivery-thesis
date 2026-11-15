package at.technikum.thesis.runner;

import java.util.List;

@FunctionalInterface
public interface CommandRunner {

    ChangeTrigger.Result run(List<String> argv, RunnerClock clock);

    static CommandRunner process() {
        return (argv, clock) -> new ChangeTrigger(argv, clock).fire();
    }
}
