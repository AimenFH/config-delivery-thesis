package at.technikum.thesis.runner;

import java.util.Arrays;
import java.util.Random;

final class Statistics {

    static final double INTERVAL_LOW = 0.025;
    static final double INTERVAL_HIGH = 0.975;

    private Statistics() {
    }

    static double percentile(double[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    static double quantile(double[] sorted, double p) {
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double low = sorted[lower];
        double high = sorted[upper];
        if (lower == upper || low == high) {
            return low;
        }
        if (Double.isNaN(low) || Double.isNaN(high)) {
            return Double.NaN;
        }
        if (Double.isInfinite(low)) {
            return Double.isInfinite(high) ? Double.NaN : low;
        }
        if (Double.isInfinite(high)) {
            return high;
        }
        return low + (position - lower) * (high - low);
    }

    static double median(double[] sorted) {
        return quantile(sorted, 0.5);
    }

    static double[] sorted(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    static double[] medianInterval(double[] values, int resamples, long seed) {
        double[] source = sorted(values);
        Random random = new Random(seed);
        double[] medians = new double[resamples];
        for (int i = 0; i < resamples; i++) {
            medians[i] = median(resample(source, random));
        }
        Arrays.sort(medians);
        return new double[]{quantile(medians, INTERVAL_LOW), quantile(medians, INTERVAL_HIGH)};
    }

    static double[] medianDifferenceInterval(double[] first, double[] second, int resamples, long seed) {
        double[] a = sorted(first);
        double[] b = sorted(second);
        Random random = new Random(seed);
        double[] differences = new double[resamples];
        for (int i = 0; i < resamples; i++) {
            double medianA = median(resample(a, random));
            double medianB = median(resample(b, random));
            differences[i] = medianA - medianB;
        }
        Arrays.sort(differences);
        return new double[]{quantile(differences, INTERVAL_LOW), quantile(differences, INTERVAL_HIGH)};
    }

    static double cliffsDelta(double[] x, double[] y) {
        long greater = 0;
        long less = 0;
        for (double a : x) {
            for (double b : y) {
                if (a > b) {
                    greater++;
                } else if (a < b) {
                    less++;
                }
            }
        }
        return (greater - less) / ((double) x.length * y.length);
    }

    static long seed(long base, String... parts) {
        long hash = base;
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                hash = 31 * hash + part.charAt(i);
            }
            hash = 31 * hash + '/';
        }
        return hash;
    }

    private static double[] resample(double[] source, Random random) {
        double[] sample = new double[source.length];
        for (int i = 0; i < sample.length; i++) {
            sample[i] = source[random.nextInt(source.length)];
        }
        Arrays.sort(sample);
        return sample;
    }
}
