package at.technikum.thesis.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ChangeTrigger {

    public record Result(String command, double startedAt, double finishedAt, int exitCode, String output) {
    }

    private final List<String> command;
    private final RunnerClock clock;

    public ChangeTrigger(List<String> command, RunnerClock clock) {
        this.command = command;
        this.clock = clock;
    }

    public Result fire() {
        double startedAt = clock.millisSinceStart();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return new Result(String.join(" ", command), startedAt, clock.millisSinceStart(), exitCode, output);
        } catch (IOException e) {
            return new Result(String.join(" ", command), startedAt, clock.millisSinceStart(), -1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(String.join(" ", command), startedAt, clock.millisSinceStart(), -1, e.toString());
        }
    }
}
