package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static at.technikum.thesis.runner.RunMetrics.Flag.FAILED_REQUESTS;
import static at.technikum.thesis.runner.RunMetrics.Flag.LOW_RESOLUTION;
import static at.technikum.thesis.runner.RunMetrics.Flag.NOT_CONVERGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryTest {

    private static final List<String> APPROACHES = List.of("way-a", "way-b");
    private static final int RESAMPLES = 2_000;
    private static final long SEED = 20261115;

    @Test
    void per_approach_and_metric_n_min_quartiles_median_and_max_come_from_the_valid_runs_only() {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(), p1(1_000)),
                run("r2", "way-a", true, true, Set.of(), p1(3_000)),
                run("r3", "way-a", true, true, Set.of(), p1(2_000)),
                run("r4", "way-a", true, true, Set.of(), p1(4_000)),
                run("r5", "way-a", false, true, Set.of(), p1(99_000))), APPROACHES, RESAMPLES, SEED);

        Summary.Row row = row(summary, Summary.METRIC, "way-a", "P1");

        assertEquals(4, row.n());
        assertEquals(0, row.censored());
        assertEquals(1_000, row.min());
        assertEquals(1_750, row.q1());
        assertEquals(2_500, row.median());
        assertEquals(3_250, row.q3());
        assertEquals(4_000, row.max());
        assertTrue(row.ciLow() >= 1_000 && row.ciLow() <= 2_500, row.toString());
        assertTrue(row.ciHigh() >= 2_500 && row.ciHigh() <= 4_000, row.toString());
        assertNull(row.cliffsDelta());
    }

    @Test
    void the_number_of_runs_valid_runs_invalid_runs_and_of_each_flag_is_reported_per_approach() {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(NOT_CONVERGED), p1(1_000)),
                run("r2", "way-a", true, true, EnumSet.of(NOT_CONVERGED, LOW_RESOLUTION), p1(1_000)),
                run("r3", "way-a", false, true, Set.of(FAILED_REQUESTS), p1(1_000)),
                run("r4", "way-b", true, true, Set.of(), p1(1_000))), APPROACHES, RESAMPLES, SEED);

        assertEquals(List.of("runs=3", "valid=2", "invalid=1", "NOT_CONVERGED=2", "REGRESSION=0",
                        "FAILED_REQUESTS=0", "LOW_RESOLUTION=1", "NOT_RECOVERED=0", "LOST_UPDATE=0",
                        "UNCONFIGURED=0", "TRIGGER_FAILED=0"),
                counts(summary, "way-a"));
        assertEquals(List.of("runs=1", "valid=1", "invalid=0", "NOT_CONVERGED=0", "REGRESSION=0",
                        "FAILED_REQUESTS=0", "LOW_RESOLUTION=0", "NOT_RECOVERED=0", "LOST_UPDATE=0",
                        "UNCONFIGURED=0", "TRIGGER_FAILED=0"),
                counts(summary, "way-b"));
    }

    @Test
    void a_censored_value_counts_as_larger_than_every_number_and_the_median_stays_defined_below_half() {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(), p1(1_000)),
                run("r2", "way-a", true, true, Set.of(), p1(2_000)),
                run("r3", "way-a", true, true, Set.of(), p1(3_000)),
                run("r4", "way-a", true, true, Set.of(NOT_CONVERGED), censoredP1()),
                run("r5", "way-a", true, true, Set.of(NOT_CONVERGED), censoredP1())), APPROACHES, RESAMPLES, SEED);

        Summary.Row row = row(summary, Summary.METRIC, "way-a", "P1");

        assertEquals(5, row.n());
        assertEquals(2, row.censored());
        assertEquals(1_000, row.min());
        assertEquals(2_000, row.q1());
        assertEquals(3_000, row.median());
        assertEquals(Double.POSITIVE_INFINITY, row.q3());
        assertEquals(Double.POSITIVE_INFINITY, row.max());
    }

    @Test
    void a_median_that_lands_on_a_censored_value_is_written_as_censored(@TempDir Path dir) throws Exception {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(), p1(1_000)),
                run("r2", "way-a", true, true, Set.of(NOT_CONVERGED), censoredP1()),
                run("r3", "way-a", true, true, Set.of(NOT_CONVERGED), censoredP1())), List.of("way-a"), RESAMPLES, SEED);
        Path file = dir.resolve("summary.csv");

        summary.writeCsv(file, 1);

        assertEquals(Double.POSITIVE_INFINITY, row(summary, Summary.METRIC, "way-a", "P1").median());
        assertTrue(Files.readAllLines(file).stream()
                .anyMatch(line -> line.startsWith("1,metric,way-a,,P1,3,,2,1000,censored,censored,censored,censored,")),
                Files.readString(file));
    }

    @Test
    void a_column_without_values_gets_a_row_with_n_0_and_empty_statistics() {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(), new Metric(MetricId.P2, "P2", null, false, null),
                        new Metric(MetricId.O5, "O5_serviceCoupling", null, false, "none"))),
                List.of("way-a"), RESAMPLES, SEED);

        Summary.Row row = row(summary, Summary.METRIC, "way-a", "O5_serviceCoupling");

        assertEquals(0, row.n());
        assertNull(row.median());
        assertNull(row.ciLow());
        assertEquals(0, row(summary, Summary.METRIC, "way-a", "P2").n());
    }

    @Test
    void two_approaches_are_compared_on_the_primary_metrics_by_difference_of_medians_and_cliffs_delta() {
        Summary summary = Summary.of(twoApproaches(), APPROACHES, RESAMPLES, SEED);

        Summary.Row row = row(summary, Summary.DIFFERENCE, "way-a", "P1");

        assertEquals("way-b", row.other());
        assertEquals(3, row.n());
        assertEquals(3, row.nOther());
        assertEquals(2_000 - 5_000, row.median());
        assertEquals(-1, row.cliffsDelta());
        assertTrue(row.ciLow() >= 1_000 - 6_000 && row.ciHigh() <= 3_000 - 4_000, row.toString());
        assertTrue(row.ciLow() <= -3_000 && -3_000 <= row.ciHigh(), row.toString());
    }

    @Test
    void only_primary_metrics_get_comparison_rows_and_every_pair_of_approaches_gets_one() {
        List<RunMetrics> runs = new ArrayList<>(twoApproaches());
        runs.add(run("c1", "way-c", true, true, Set.of(), p1(500), p2(100)));

        Summary summary = Summary.of(runs, List.of("way-a", "way-b", "way-c"), RESAMPLES, SEED);

        assertEquals(List.of("way-a|way-b|P1", "way-a|way-c|P1", "way-b|way-c|P1"),
                summary.rows(Summary.DIFFERENCE).stream()
                        .map(row -> row.approach() + "|" + row.other() + "|" + row.metric()).toList());
        assertEquals(1, row(summary, Summary.DIFFERENCE, "way-b", "P1", "way-c").cliffsDelta());
    }

    @Test
    void a_difference_of_medians_that_rests_on_a_censored_median_is_censored() {
        Summary summary = Summary.of(List.of(
                run("a1", "way-a", true, true, Set.of(), censoredP1()),
                run("b1", "way-b", true, true, Set.of(), p1(1_000)),
                run("b2", "way-b", true, true, Set.of(), censoredP1())), APPROACHES, RESAMPLES, SEED);

        Summary.Row row = row(summary, Summary.DIFFERENCE, "way-a", "P1");

        assertFalse(Double.isFinite(row.median()));
        assertEquals(2, row.censored());
        assertEquals((1 - 0) / 2.0, row.cliffsDelta());
    }

    @Test
    void a1_and_f1_are_also_summarised_over_all_runs_with_a_successful_commit_valid_or_not() {
        Summary summary = Summary.of(List.of(
                run("r1", "way-a", true, true, Set.of(), a1(0.0), f1(-75)),
                run("r2", "way-a", true, true, Set.of(), a1(0.1), f1(5)),
                run("r3", "way-a", false, true, Set.of(), a1(0.5), f1(900)),
                run("r4", "way-a", false, false, Set.of(), a1(0.9), f1(2_000))), List.of("way-a"), RESAMPLES, SEED);

        Summary.Row sensitivity = row(summary, Summary.SENSITIVITY, "way-a", "A1");
        Summary.Row valid = row(summary, Summary.METRIC, "way-a", "A1");

        assertEquals(3, sensitivity.n());
        assertEquals(0.1, sensitivity.median());
        assertEquals(0.5, sensitivity.max());
        assertEquals(2, valid.n());
        assertEquals(0.1, valid.max());
        assertEquals(900, row(summary, Summary.SENSITIVITY, "way-a", "F1").max());
        assertEquals(List.of("A1", "F1"), summary.rows(Summary.SENSITIVITY).stream().map(Summary.Row::metric).toList());
    }

    @Test
    void the_bootstrap_of_a_row_is_seeded_from_the_stored_seed_combined_with_approach_and_column() {
        double[] values = {1_000, 1_200, 1_250, 1_400, 1_900, 2_300, 2_350, 4_000, 9_000, 9_100};
        List<RunMetrics> runs = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            runs.add(run("a" + i, "way-a", true, true, Set.of(), p1(values[i]), p2(values[i])));
            runs.add(run("b" + i, "way-b", true, true, Set.of(), p1(values[i]), p2(values[i])));
        }

        Summary summary = Summary.of(runs, APPROACHES, 40, SEED);

        for (String approach : APPROACHES) {
            for (String column : List.of("P1", "P2")) {
                double[] expected = Statistics.medianInterval(values, 40, Statistics.seed(SEED, approach, column));
                Summary.Row row = row(summary, Summary.METRIC, approach, column);
                assertEquals(expected[0], row.ciLow(), approach + " " + column);
                assertEquals(expected[1], row.ciHigh(), approach + " " + column);
            }
        }
        double[] difference = Statistics.medianDifferenceInterval(values, values, 40,
                Statistics.seed(SEED, "way-a", "way-b", "P1"));
        assertEquals(difference[0], row(summary, Summary.DIFFERENCE, "way-a", "P1").ciLow());
        assertEquals(difference[1], row(summary, Summary.DIFFERENCE, "way-a", "P1").ciHigh());
        assertFalse(row(summary, Summary.METRIC, "way-a", "P1").ciLow()
                .equals(row(summary, Summary.METRIC, "way-b", "P1").ciLow())
                && row(summary, Summary.METRIC, "way-a", "P1").ciHigh()
                .equals(row(summary, Summary.METRIC, "way-b", "P1").ciHigh())
                && row(summary, Summary.METRIC, "way-a", "P2").ciLow()
                .equals(row(summary, Summary.METRIC, "way-a", "P1").ciLow())
                && row(summary, Summary.METRIC, "way-a", "P2").ciHigh()
                .equals(row(summary, Summary.METRIC, "way-a", "P1").ciHigh()));
    }

    @Test
    void approaches_keep_the_order_of_the_campaign_and_metrics_the_order_of_the_runs() {
        Summary summary = Summary.of(twoApproaches(), List.of("way-b", "way-a"), RESAMPLES, SEED);

        assertEquals(List.of("way-b|P1", "way-b|P2", "way-a|P1", "way-a|P2"),
                summary.rows(Summary.METRIC).stream().map(row -> row.approach() + "|" + row.metric()).toList());
        assertEquals("way-b", summary.rows(Summary.COUNT).get(0).approach());
        assertEquals("way-b", summary.rows(Summary.DIFFERENCE).get(0).approach());
        assertEquals(1, summary.rows(Summary.DIFFERENCE).get(0).cliffsDelta());
    }

    @Test
    void the_order_of_the_runs_does_not_change_the_summary() {
        List<RunMetrics> shuffled = new ArrayList<>(twoApproaches());
        Collections.reverse(shuffled);

        assertEquals(Summary.of(twoApproaches(), APPROACHES, RESAMPLES, SEED).rows(),
                Summary.of(shuffled, APPROACHES, RESAMPLES, SEED).rows());
    }

    @Test
    void the_same_seed_gives_the_same_summary_and_the_csv_has_one_line_per_row_with_unix_line_ends(
            @TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.csv");
        Path second = dir.resolve("second.csv");

        Summary.of(twoApproaches(), APPROACHES, RESAMPLES, SEED).writeCsv(first, 1);
        Summary.of(twoApproaches(), APPROACHES, RESAMPLES, SEED).writeCsv(second, 1);

        String text = new String(Files.readAllBytes(first), StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(first);
        assertEquals(text, new String(Files.readAllBytes(second), StandardCharsets.UTF_8));
        assertFalse(text.contains("\r"));
        assertTrue(text.endsWith("\n"));
        assertEquals("protocolVersion,kind,approach,other,metric,n,n_other,censored,min,q1,median,q3,max,"
                + "ci_low,ci_high,cliffs_delta", lines.get(0));
        assertTrue(lines.subList(1, lines.size()).stream().allMatch(line -> line.startsWith("1,")));
        assertEquals(1 + 2 * 11 + 2 * 2 + 1, lines.size());
        assertEquals("1,count,way-a,,runs,3,,,,,,,,,,", lines.get(1));
        assertTrue(lines.get(23).startsWith("1,metric,way-a,,P1,3,,0,1000,1500,2000,2500,3000,"), lines.get(23));
        assertTrue(lines.get(27).startsWith("1,difference,way-a,way-b,P1,3,3,0,,,-3000,,,"), lines.get(27));
        assertTrue(lines.get(27).endsWith(",-1"), lines.get(27));
    }

    private static List<RunMetrics> twoApproaches() {
        return List.of(
                run("a1", "way-a", true, true, Set.of(), p1(1_000), p2(900)),
                run("a2", "way-a", true, true, Set.of(), p1(2_000), p2(1_900)),
                run("a3", "way-a", true, true, Set.of(), p1(3_000), p2(2_900)),
                run("b1", "way-b", true, true, Set.of(), p1(4_000), p2(3_900)),
                run("b2", "way-b", true, true, Set.of(), p1(5_000), p2(4_900)),
                run("b3", "way-b", true, true, Set.of(), p1(6_000), p2(5_900)));
    }

    private static Metric p1(double value) {
        return new Metric(MetricId.P1, "P1", value, false, null);
    }

    private static Metric censoredP1() {
        return new Metric(MetricId.P1, "P1", 150_000.0, true, null);
    }

    private static Metric p2(double value) {
        return new Metric(MetricId.P2, "P2", value, false, null);
    }

    private static Metric a1(double value) {
        return new Metric(MetricId.A1, "A1", value, false, null);
    }

    private static Metric f1(double value) {
        return new Metric(MetricId.F1, "F1", value, false, null);
    }

    private static RunMetrics run(String runId, String approach, boolean valid, boolean commitSucceeded,
                                  Set<RunMetrics.Flag> flags, Metric... metrics) {
        return new RunMetrics(runId, approach, "s1-valid-change", 1,
                valid ? RunMetrics.Verdict.VALID : RunMetrics.Verdict.INVALID,
                valid ? List.of() : List.of("baseline: 1 failed requests"), flags, commitSucceeded, List.of(metrics));
    }

    private static Summary.Row row(Summary summary, String kind, String approach, String metric) {
        return summary.rows(kind).stream()
                .filter(row -> row.approach().equals(approach) && row.metric().equals(metric))
                .findFirst().orElseThrow();
    }

    private static Summary.Row row(Summary summary, String kind, String approach, String metric, String other) {
        return summary.rows(kind).stream()
                .filter(row -> row.approach().equals(approach) && row.metric().equals(metric)
                        && other.equals(row.other()))
                .findFirst().orElseThrow();
    }

    private static List<String> counts(Summary summary, String approach) {
        return summary.rows(Summary.COUNT).stream()
                .filter(row -> row.approach().equals(approach))
                .map(row -> row.metric() + "=" + row.n()).toList();
    }
}
