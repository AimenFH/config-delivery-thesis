package at.technikum.thesis.runner;

import java.util.List;

public interface Sampler {

    void start(RunnerClock clock);

    List<ResourceSample> stop();
}
