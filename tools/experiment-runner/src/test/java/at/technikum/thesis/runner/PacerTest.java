package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PacerTest {

    private final Pacer pacer = Pacer.real();

    @Test
    void sleeping_until_a_time_returns_when_the_clock_has_reached_it() throws Exception {
        RunnerClock clock = new RunnerClock();

        pacer.sleepUntil(clock, 80);

        double now = clock.millisSinceStart();
        assertTrue(now >= 80, "returned at " + now + " ms");
        assertTrue(now < 2_000, "returned at " + now + " ms");
    }

    @Test
    void sleeping_until_a_time_that_has_passed_returns_at_once() throws Exception {
        RunnerClock clock = new RunnerClock();
        pacer.sleepUntil(clock, 30);

        double before = clock.millisSinceStart();
        pacer.sleepUntil(clock, 10);

        assertTrue(clock.millisSinceStart() - before < 20, "waited although the time had passed");
    }

    @Test
    void now_is_the_time_of_the_given_clock() throws Exception {
        RunnerClock clock = new RunnerClock();

        double before = clock.millisSinceStart();
        double now = pacer.now(clock);
        double after = clock.millisSinceStart();

        assertTrue(before <= now && now <= after, before + " <= " + now + " <= " + after);
    }
}
