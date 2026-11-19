package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeTriggerTest {

    private final RunnerClock clock = new RunnerClock();

    @Test
    void fireReturnsTheExitCodeAndTheOutputOfBothStreams() {
        ChangeTrigger.Result result = new ChangeTrigger(
                List.of("sh", "-c", "echo out; echo err 1>&2; exit 3"), clock).fire();

        assertEquals(3, result.exitCode());
        assertEquals("out\nerr", result.output());
        assertEquals("sh -c echo out; echo err 1>&2; exit 3", result.command());
        assertTrue(result.finishedAt() >= result.startedAt(), result.toString());
    }

    @Test
    void aCommandThatEndsBeforeTheTimeoutKeepsItsExitCode() {
        ChangeTrigger.Result result = new ChangeTrigger(List.of("sh", "-c", "echo done"), clock).fire(5_000);

        assertEquals(0, result.exitCode());
        assertEquals("done", result.output());
    }

    @Test
    void aCommandThatRunsLongerThanTheTimeoutIsStoppedWithExitCode124() {
        ChangeTrigger.Result result = new ChangeTrigger(
                List.of("sh", "-c", "echo started; sleep 30"), clock).fire(300);

        assertEquals(ChangeTrigger.TIMED_OUT, result.exitCode());
        assertEquals("started\ntimed out after 300 ms", result.output());
        double took = result.finishedAt() - result.startedAt();
        assertTrue(took >= 300 && took < 5_000, "took " + took + " ms");
    }

    @Test
    void aTimeoutOfZeroWaitsForTheCommand() {
        ChangeTrigger.Result result = new ChangeTrigger(List.of("sh", "-c", "sleep 0.4; echo late"), clock).fire(0);

        assertEquals(0, result.exitCode());
        assertEquals("late", result.output());
    }

    @Test
    void aCommandThatCannotBeStartedReturnsExitCodeMinus1() {
        ChangeTrigger.Result result = new ChangeTrigger(List.of("no-such-command-of-the-runner"), clock).fire(1_000);

        assertEquals(-1, result.exitCode());
        assertTrue(result.output().contains("IOException"), result.output());
    }
}
