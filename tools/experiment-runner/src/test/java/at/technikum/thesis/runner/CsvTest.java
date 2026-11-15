package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvTest {

    @Test
    void a_censored_run_value_is_written_as_a_greater_than_sign_followed_by_the_number() {
        assertEquals(">150000", Csv.metric(new Metric(MetricId.P1, "P1", 150_000.0, true, null)));
        assertEquals(">149920.5", Csv.metric(new Metric(MetricId.R1, "R1", 149_920.5, true, null)));
        assertEquals("5342", Csv.metric(new Metric(MetricId.P1, "P1", 5_342.0, false, null)));
    }

    @Test
    void a_metric_without_a_number_is_written_as_its_text_or_left_empty() {
        assertEquals("none", Csv.metric(new Metric(MetricId.O5, "O5_serviceCoupling", null, false, "none")));
        assertEquals("", Csv.metric(new Metric(MetricId.P2, "P2", null, false, null)));
    }

    @Test
    void a_censored_statistic_is_written_as_the_word_censored_and_a_missing_one_as_an_empty_cell() {
        assertEquals("censored", Csv.number(Double.POSITIVE_INFINITY));
        assertEquals("censored", Csv.number(Double.NaN));
        assertEquals("", Csv.number((Double) null));
    }

    @Test
    void numbers_are_written_in_plain_notation_with_a_point_in_every_locale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            assertEquals("0.000133", Csv.number(0.000133));
            assertEquals("1234567.25", Csv.number(1_234_567.25));
            assertEquals("-75", Csv.number(-75.0));
            assertEquals("0", Csv.number(0.0));
            assertEquals("0", Csv.number(-0.0));
            assertEquals("12300000000", Csv.number(1.23e10));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void a_cell_with_a_comma_a_quote_or_a_line_break_is_quoted() {
        assertEquals("plain text", Csv.cell("plain text"));
        assertEquals("\"config client and bus libraries, startup dependency\"",
                Csv.cell("config client and bus libraries, startup dependency"));
        assertEquals("\"said \"\"no\"\"\"", Csv.cell("said \"no\""));
        assertEquals("\"two\nlines\"", Csv.cell("two\nlines"));
        assertEquals("", Csv.cell(null));
    }

    @Test
    void every_row_ends_with_a_unix_line_end(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.csv");

        Csv.write(file, List.of(List.of("a", "b"), List.of("1", "x, y")));

        assertEquals("a,b\n1,\"x, y\"\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
