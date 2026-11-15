package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ResourceMetricsParser {

    public record Reading(
            String node,
            String namespace,
            String pod,
            String container,
            double cpuSeconds,
            long workingSetBytes,
            long sourceTimestampMillis
    ) {
    }

    static final String CPU_FAMILY = "container_cpu_usage_seconds_total";
    static final String MEMORY_FAMILY = "container_memory_working_set_bytes";

    private record Key(String namespace, String pod, String container) implements Comparable<Key> {

        private static final Comparator<Key> ORDER = Comparator.comparing(Key::namespace)
                .thenComparing(Key::pod)
                .thenComparing(Key::container);

        @Override
        public int compareTo(Key other) {
            return ORDER.compare(this, other);
        }
    }

    private record Line(String family, Key key, double value, long timestampMillis) {
    }

    private ResourceMetricsParser() {
    }

    public static List<Reading> parse(String text, String node) {
        Map<Key, Line> cpu = new TreeMap<>();
        Map<Key, Line> memory = new HashMap<>();
        if (text != null) {
            for (String raw : text.split("\n")) {
                Line line = line(raw.trim());
                if (line == null) {
                    continue;
                }
                if (line.family().equals(CPU_FAMILY)) {
                    cpu.put(line.key(), line);
                } else {
                    memory.put(line.key(), line);
                }
            }
        }
        List<Reading> readings = new ArrayList<>();
        for (Map.Entry<Key, Line> entry : cpu.entrySet()) {
            Line workingSet = memory.get(entry.getKey());
            if (workingSet == null) {
                continue;
            }
            Key key = entry.getKey();
            readings.add(new Reading(node, key.namespace(), key.pod(), key.container(),
                    entry.getValue().value(), Math.round(workingSet.value()), entry.getValue().timestampMillis()));
        }
        return readings;
    }

    private static Line line(String text) {
        if (text.isEmpty() || text.charAt(0) == '#') {
            return null;
        }
        int brace = text.indexOf('{');
        if (brace < 0) {
            return null;
        }
        String family = text.substring(0, brace);
        if (!family.equals(CPU_FAMILY) && !family.equals(MEMORY_FAMILY)) {
            return null;
        }
        Map<String, String> labels = new HashMap<>();
        int position = labels(text, brace + 1, labels);
        if (position < 0) {
            return null;
        }
        String namespace = labels.get("namespace");
        String pod = labels.get("pod");
        String container = labels.get("container");
        if (namespace == null || pod == null || container == null) {
            return null;
        }
        String rest = text.substring(position).trim();
        if (rest.isEmpty()) {
            return null;
        }
        String[] fields = rest.split("\\s+");
        if (fields.length > 2) {
            return null;
        }
        try {
            double value = Double.parseDouble(fields[0]);
            if (!Double.isFinite(value)) {
                return null;
            }
            long timestamp = fields.length == 2 ? Long.parseLong(fields[1]) : 0;
            return new Line(family, new Key(namespace, pod, container), value, timestamp);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int labels(String text, int from, Map<String, String> labels) {
        int position = from;
        while (true) {
            if (position >= text.length()) {
                return -1;
            }
            if (text.charAt(position) == '}') {
                return position + 1;
            }
            int equals = text.indexOf('=', position);
            if (equals < 0 || equals + 1 >= text.length() || text.charAt(equals + 1) != '"') {
                return -1;
            }
            String name = text.substring(position, equals).trim();
            StringBuilder value = new StringBuilder();
            int cursor = equals + 2;
            boolean closed = false;
            while (cursor < text.length()) {
                char c = text.charAt(cursor);
                if (c == '\\' && cursor + 1 < text.length()) {
                    char escaped = text.charAt(cursor + 1);
                    value.append(escaped == 'n' ? '\n' : escaped);
                    cursor += 2;
                } else if (c == '"') {
                    closed = true;
                    cursor++;
                    break;
                } else {
                    value.append(c);
                    cursor++;
                }
            }
            if (!closed || name.isEmpty()) {
                return -1;
            }
            labels.put(name, value.toString());
            if (cursor < text.length() && text.charAt(cursor) == ',') {
                cursor++;
            } else if (cursor >= text.length() || text.charAt(cursor) != '}') {
                return -1;
            }
            position = cursor;
        }
    }
}
