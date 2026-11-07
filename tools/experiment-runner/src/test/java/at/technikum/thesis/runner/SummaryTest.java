package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryTest {

    @Test
    void usesOnlyValidRuns() {
        Summary summary = Summary.of(List.of(
                entry("way-a", true, 1000),
                entry("way-a", true, 3000),
                entry("way-a", true, 2000),
                entry("way-a", false, 99000)));

        assertEquals(new Summary.Count("way-a", 4, 3), summary.counts().get(0));
        Summary.Stat propagation = stat(summary, "way-a", "propagationMillis");
        assertEquals(3, propagation.n());
        assertEquals(1000, propagation.min());
        assertEquals(2000, propagation.median());
        assertEquals(3000, propagation.max());
        assertFalse(summary.allValid());
    }

    @Test
    void reachedNeedsEnoughValidRunsInEveryWay() {
        Summary summary = Summary.of(List.of(
                entry("way-a", true, 1000),
                entry("way-a", false, 1000),
                entry("way-a", true, 1000),
                entry("way-b", true, 1000)));

        assertTrue(summary.reached(1));
        assertFalse(summary.reached(2));
        assertFalse(Summary.of(List.of()).reached(1));
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheMiddleTwo() {
        assertEquals(2.5, Summary.median(new double[]{1, 2, 3, 4}));
        assertEquals(2, Summary.median(new double[]{1, 2, 3}));
    }

    @Test
    void waysKeepTheirOrderAndAreSeparate() {
        Summary summary = Summary.of(List.of(
                entry("way-b", true, 90000),
                entry("way-a", true, 7000),
                entry("way-b", true, 91000)));

        assertEquals(List.of("way-b", "way-a"), summary.counts().stream().map(Summary.Count::way).toList());
        assertEquals(90500, stat(summary, "way-b", "propagationMillis").median());
        assertEquals(7000, stat(summary, "way-a", "propagationMillis").median());
        assertTrue(summary.allValid());
    }

    @Test
    void runWithoutMeasurableWindowIsCountedButNotInTheStats() {
        Summary summary = Summary.of(List.of(
                entry("way-a", true, 1000),
                new Summary.Entry("way-a", result(true, null))));

        assertEquals(new Summary.Count("way-a", 2, 2), summary.counts().get(0));
        assertEquals(1, stat(summary, "way-a", "propagationMillis").n());
    }

    @Test
    void writesOneCsvLinePerWayAndMetric(@TempDir Path dir) throws Exception {
        Summary summary = Summary.of(List.of(entry("way-a", true, 1000), entry("way-b", true, 2000)));
        Path file = dir.resolve("summary.csv");

        summary.writeCsv(file);

        List<String> lines = Files.readAllLines(file);
        assertEquals("way,metric,n,min,median,max", lines.get(0));
        assertEquals(1 + 2 * 5, lines.size());
        assertTrue(lines.contains("way-a,propagationMillis,1,1000.0,1000.0,1000.0"), lines.toString());
    }

    private static Summary.Stat stat(Summary summary, String way, String metric) {
        return summary.stats().stream()
                .filter(s -> s.way().equals(way) && s.metric().equals(metric))
                .findFirst().orElseThrow();
    }

    private static Summary.Entry entry(String way, boolean valid, double propagation) {
        WindowDetector.Result window = new WindowDetector.Result("v1", "v2", 5000, 5100,
                5100 + propagation, 5100 + propagation + 100, 100.0, 300.0,
                propagation, propagation + 100, 0, List.of());
        return new Summary.Entry(way, result(valid, window));
    }

    private static RunResult result(boolean valid, WindowDetector.Result window) {
        return new RunResult("run", "label", "", 0, "", 50, 90, 5, 3, 1000,
                0, 0, 0, valid, List.of(), null, window);
    }
}
