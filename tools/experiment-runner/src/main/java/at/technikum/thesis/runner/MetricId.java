package at.technikum.thesis.runner;

public enum MetricId {

    P1("Propagation time", "Convergence time", "ms", true),
    P2("Propagation time", "Time to first activation", "ms", false),
    P3("Propagation time", "Commit duration", "ms", false),
    C1("Consistency", "Inconsistency window", "ms", true),
    C2("Consistency", "Client-visible reversions", "count", false),
    C3("Consistency", "Pod regressions", "count", false),
    A1("Availability", "Failed-request ratio", "ratio", true),
    A2("Availability", "Longest outage", "ms", false),
    A3("Availability", "Lowest serving replicas", "count", false),
    F1("Performance impact", "Excess slow requests", "count", true),
    F2("Performance impact", "Latency percentiles", "ms", false),
    F3("Performance impact", "Worst second", "ms", false),
    O1("Operational overhead", "Infrastructure CPU", "mCPU", true),
    O2("Operational overhead", "Infrastructure memory", "MiB", true),
    O3("Operational overhead", "Workload cost", "mCPU, MiB", false),
    O4("Operational overhead", "Pods replaced", "count", false),
    O5("Operational overhead", "Declared facts", "table", false),
    E1("S2 invalid configuration", "Exposed replicas", "count", true),
    E2("S2 invalid configuration", "Exposed requests", "count", true),
    E3("S2 invalid configuration", "Availability under the bad configuration", "ratio, ms", false),
    E4("S2 invalid configuration", "Lowest serving replicas", "count", false),
    R1("S2 invalid configuration", "Recovery time", "ms", true),
    U1("S3 infrastructure outage", "Delivery during the outage", "count", false),
    U2("S3 infrastructure outage", "Replacement pod", "ms, version", false),
    U3("S3 infrastructure outage", "Mixed-version time", "ms", true),
    U4("S3 infrastructure outage", "Catch-up time", "ms", true),
    U5("S3 infrastructure outage", "Failed-request ratio", "ratio", false),
    Q1("S4 rapid successive changes", "Final convergence time", "ms", true),
    Q2("S4 rapid successive changes", "Mixed-version time", "ms", true),
    Q3("S4 rapid successive changes", "Most versions at once", "count", false),
    Q4("S4 rapid successive changes", "Intermediate version exposure", "count", false),
    Q5("S4 rapid successive changes", "Pod regressions", "count", false),
    Q6("S4 rapid successive changes", "Failed-request ratio", "ratio", false),
    Q7("S4 rapid successive changes", "Commit gap", "ms", false);

    private final String criterion;
    private final String title;
    private final String unit;
    private final boolean primary;

    MetricId(String criterion, String title, String unit, boolean primary) {
        this.criterion = criterion;
        this.title = title;
        this.unit = unit;
        this.primary = primary;
    }

    public String criterion() {
        return criterion;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public boolean primary() {
        return primary;
    }
}
