package at.technikum.thesis.runner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public interface ServiceProbe {

    interface Load {

        double startedAt();

        long scheduledCount();

        List<Observation> stop() throws InterruptedException;
    }

    List<Observation> probe(int count, RunnerClock clock);

    Load startLoad(RunnerClock clock);

    static ServiceProbe http(Protocol protocol) {
        return new ServiceProbe() {
            @Override
            public List<Observation> probe(int count, RunnerClock clock) {
                ProbeClient client = new ProbeClient(protocol.uri(), clock,
                        Duration.ofMillis(protocol.requestTimeoutMillis()));
                List<Observation> observations = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    double now = clock.millisSinceStart();
                    observations.add(client.probe(i, now, now).join());
                }
                return observations;
            }

            @Override
            public Load startLoad(RunnerClock clock) {
                LoadGenerator generator = new LoadGenerator(new ProbeClient(protocol.uri(), clock,
                        Duration.ofMillis(protocol.requestTimeoutMillis())), clock, protocol.ratePerSecond());
                generator.start();
                return new Load() {
                    @Override
                    public double startedAt() {
                        return generator.startedAt();
                    }

                    @Override
                    public long scheduledCount() {
                        return generator.scheduledCount();
                    }

                    @Override
                    public List<Observation> stop() throws InterruptedException {
                        return generator.stop();
                    }
                };
            }
        };
    }
}
