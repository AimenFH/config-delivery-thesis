package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricIdTest {

    @Test
    void theRegistryHoldsEveryMetricIdOfThePlan() {
        assertEquals(List.of("P1", "P2", "P3", "C1", "C2", "C3", "A1", "A2", "A3", "F1", "F2", "F3",
                        "O1", "O2", "O3", "O4", "O5", "E1", "E2", "E3", "E4", "R1", "U1", "U2", "U3", "U4", "U5"),
                Arrays.stream(MetricId.values()).map(Enum::name).toList());
    }

    @Test
    void thePrimaryMetricsAreThoseThePlanMarks() {
        assertEquals(EnumSet.of(MetricId.P1, MetricId.C1, MetricId.A1, MetricId.F1, MetricId.O1, MetricId.O2,
                        MetricId.E1, MetricId.E2, MetricId.R1, MetricId.U3, MetricId.U4),
                EnumSet.copyOf(Arrays.stream(MetricId.values()).filter(MetricId::primary).toList()));
    }

    @Test
    void criteriaFollowTheLetterOfTheId() {
        for (MetricId id : MetricId.values()) {
            String expected = switch (id.name().charAt(0)) {
                case 'P' -> "Propagation time";
                case 'C' -> "Consistency";
                case 'A' -> "Availability";
                case 'F' -> "Performance impact";
                case 'O' -> "Operational overhead";
                case 'E', 'R' -> "S2 invalid configuration";
                default -> "S3 infrastructure outage";
            };
            assertEquals(expected, id.criterion(), id.name());
        }
    }

    @Test
    void titlesAndUnitsAreThoseOfThePlan() {
        assertEquals(List.of("Convergence time|ms", "Time to first activation|ms", "Commit duration|ms",
                        "Inconsistency window|ms", "Client-visible reversions|count", "Pod regressions|count",
                        "Failed-request ratio|ratio", "Longest outage|ms", "Lowest serving replicas|count",
                        "Excess slow requests|count", "Latency percentiles|ms", "Worst second|ms",
                        "Infrastructure CPU|mCPU", "Infrastructure memory|MiB", "Workload cost|mCPU, MiB",
                        "Pods replaced|count", "Declared facts|table",
                        "Exposed replicas|count", "Exposed requests|count",
                        "Availability under the bad configuration|ratio, ms", "Lowest serving replicas|count",
                        "Recovery time|ms",
                        "Delivery during the outage|count", "Replacement pod|ms, version", "Mixed-version time|ms",
                        "Catch-up time|ms", "Failed-request ratio|ratio"),
                Arrays.stream(MetricId.values()).map(id -> id.title() + "|" + id.unit()).toList());
    }
}
