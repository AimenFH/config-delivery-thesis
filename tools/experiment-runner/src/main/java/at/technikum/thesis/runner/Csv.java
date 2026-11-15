package at.technikum.thesis.runner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class Csv {

    static final String CENSORED = "censored";

    private Csv() {
    }

    static String number(Double value) {
        if (value == null) {
            return "";
        }
        if (!Double.isFinite(value)) {
            return CENSORED;
        }
        return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
    }

    static String number(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    static String metric(Metric metric) {
        if (metric == null) {
            return "";
        }
        if (metric.value() != null) {
            return (metric.censored() ? ">" : "") + number(metric.value());
        }
        return metric.text() == null ? "" : metric.text();
    }

    static String cell(String text) {
        if (text == null) {
            return "";
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    static String line(List<String> cells) {
        return String.join(",", cells.stream().map(Csv::cell).toList());
    }

    static void write(Path file, List<List<String>> rows) throws IOException {
        StringBuilder text = new StringBuilder();
        for (List<String> row : rows) {
            text.append(line(row)).append('\n');
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
