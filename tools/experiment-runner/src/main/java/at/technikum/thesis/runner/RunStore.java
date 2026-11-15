package at.technikum.thesis.runner;

import java.io.IOException;

public interface RunStore {

    void save(RunRecord record, RunMetrics metrics) throws IOException;
}
