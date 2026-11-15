package at.technikum.thesis.runner;

import java.util.concurrent.TimeUnit;

public interface Pacer {

    double now(RunnerClock clock);

    void sleepUntil(RunnerClock clock, double millis) throws InterruptedException;

    static Pacer real() {
        return new Pacer() {
            @Override
            public double now(RunnerClock clock) {
                return clock.millisSinceStart();
            }

            @Override
            public void sleepUntil(RunnerClock clock, double millis) throws InterruptedException {
                double remaining = millis - clock.millisSinceStart();
                while (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep((long) Math.ceil(remaining * 1_000_000.0));
                    remaining = millis - clock.millisSinceStart();
                }
            }
        };
    }
}
