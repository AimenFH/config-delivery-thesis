package at.technikum.thesis.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChangeTrigger {

    public static final int TIMED_OUT = 124;

    public record Result(String command, double startedAt, double finishedAt, int exitCode, String output) {
    }

    private final List<String> command;
    private final RunnerClock clock;

    public ChangeTrigger(List<String> command, RunnerClock clock) {
        this.command = command;
        this.clock = clock;
    }

    public Result fire() {
        return fire(0);
    }

    public Result fire(long timeoutMillis) {
        double startedAt = clock.millisSinceStart();
        Path captured = null;
        try {
            captured = Files.createTempFile("runner-command", ".out");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(captured.toFile())
                    .start();
            boolean finished = true;
            if (timeoutMillis > 0) {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
            }
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
            double finishedAt = clock.millisSinceStart();
            String output = new String(Files.readAllBytes(captured), StandardCharsets.UTF_8).trim();
            if (!finished) {
                String note = "timed out after " + timeoutMillis + " ms";
                return new Result(String.join(" ", command), startedAt, finishedAt, TIMED_OUT,
                        output.isEmpty() ? note : output + "\n" + note);
            }
            return new Result(String.join(" ", command), startedAt, finishedAt, process.exitValue(), output);
        } catch (IOException e) {
            return new Result(String.join(" ", command), startedAt, clock.millisSinceStart(), -1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(String.join(" ", command), startedAt, clock.millisSinceStart(), -1, e.toString());
        } finally {
            if (captured != null) {
                try {
                    Files.deleteIfExists(captured);
                } catch (IOException ignored) {
                    captured.toFile().deleteOnExit();
                }
            }
        }
    }
}
