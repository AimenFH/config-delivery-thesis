package at.technikum.thesis.runner;

import java.util.List;
import java.util.Set;

public record RunMetrics(
        String runId,
        String approach,
        String scenario,
        int block,
        Verdict verdict,
        List<String> invalidReasons,
        Set<Flag> flags,
        boolean commitSucceeded,
        List<Metric> metrics
) {

    public enum Verdict {
        VALID, INVALID
    }

    public enum Flag {
        NOT_CONVERGED, REGRESSION, FAILED_REQUESTS, LOW_RESOLUTION, NOT_RECOVERED, LOST_UPDATE
    }
}
