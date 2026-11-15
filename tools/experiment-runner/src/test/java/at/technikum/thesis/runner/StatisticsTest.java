package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsTest {

    private static final double CENSORED = Double.POSITIVE_INFINITY;

    @Test
    void quartiles_are_interpolated_linearly_at_position_p_times_n_minus_1() {
        double[] five = {1, 2, 3, 4, 5};
        double[] four = {10, 20, 30, 40};

        assertEquals(2, Statistics.quantile(five, 0.25));
        assertEquals(3, Statistics.quantile(five, 0.5));
        assertEquals(4, Statistics.quantile(five, 0.75));
        assertEquals(17.5, Statistics.quantile(four, 0.25));
        assertEquals(25, Statistics.quantile(four, 0.5));
        assertEquals(32.5, Statistics.quantile(four, 0.75));
        assertEquals(10, Statistics.quantile(four, 0));
        assertEquals(40, Statistics.quantile(four, 1));
    }

    @Test
    void the_quartiles_of_a_single_value_are_that_value() {
        double[] one = {7};

        assertEquals(7, Statistics.quantile(one, 0.25));
        assertEquals(7, Statistics.median(one));
        assertEquals(7, Statistics.quantile(one, 0.75));
    }

    @Test
    void the_median_stays_defined_while_fewer_than_half_of_the_values_are_censored() {
        assertEquals(300, Statistics.median(new double[]{100, 200, 300, CENSORED, CENSORED}));
        assertEquals(250, Statistics.median(new double[]{100, 200, 300, CENSORED}));
    }

    @Test
    void a_median_that_lands_on_a_censored_value_is_censored() {
        assertEquals(CENSORED, Statistics.median(new double[]{100, 200, CENSORED, CENSORED}));
        assertEquals(CENSORED, Statistics.median(new double[]{100, CENSORED, CENSORED}));
        assertEquals(CENSORED, Statistics.median(new double[]{100, CENSORED}));
        assertEquals(CENSORED, Statistics.median(new double[]{CENSORED, CENSORED}));
    }

    @Test
    void censored_values_enter_as_larger_than_every_number() {
        double[] sorted = Statistics.sorted(new double[]{CENSORED, 500_000, 3, CENSORED, 20});

        assertArrayEquals(new double[]{3, 20, 500_000, CENSORED, CENSORED}, sorted);
        assertEquals(20, Statistics.quantile(sorted, 0.25));
        assertEquals(CENSORED, Statistics.quantile(sorted, 0.75));
        assertFalseFinite(Statistics.quantile(new double[]{1, 2, 3, CENSORED}, 0.75));
    }

    @Test
    void latency_percentiles_use_the_nearest_rank_index_ceil_p_times_n_minus_1() {
        double[] hundred = new double[100];
        for (int i = 0; i < 100; i++) {
            hundred[i] = i + 1;
        }

        assertEquals(50, Statistics.percentile(hundred, 50));
        assertEquals(95, Statistics.percentile(hundred, 95));
        assertEquals(99, Statistics.percentile(hundred, 99));
        assertEquals(100, Statistics.percentile(hundred, 100));
        assertEquals(30, Statistics.percentile(new double[]{10, 20, 30, 40}, 51));
        assertEquals(20, Statistics.percentile(new double[]{10, 20, 30, 40}, 50));
    }

    @Test
    void cliffs_delta_is_1_or_minus_1_for_full_separation() {
        assertEquals(1, Statistics.cliffsDelta(new double[]{5, 6, 7}, new double[]{1, 2, 3}));
        assertEquals(-1, Statistics.cliffsDelta(new double[]{1, 2, 3}, new double[]{5, 6, 7}));
    }

    @Test
    void cliffs_delta_is_0_for_complete_overlap() {
        assertEquals(0, Statistics.cliffsDelta(new double[]{1, 2, 3}, new double[]{1, 2, 3}));
        assertEquals(0, Statistics.cliffsDelta(new double[]{4, 4}, new double[]{4, 4, 4}));
    }

    @Test
    void cliffs_delta_of_a_mixed_case_is_greater_pairs_minus_smaller_pairs_over_n_times_m() {
        assertEquals((5 - 4) / 9.0, Statistics.cliffsDelta(new double[]{1, 4, 6}, new double[]{2, 3, 5}));
        assertEquals((1 - 6) / 8.0, Statistics.cliffsDelta(new double[]{1, 3}, new double[]{2, 3, 4, 5}));
    }

    @Test
    void cliffs_delta_counts_a_censored_value_as_larger_than_every_number_and_two_censored_values_as_a_tie() {
        assertEquals((2 - 0) / 4.0, Statistics.cliffsDelta(new double[]{CENSORED, CENSORED}, new double[]{CENSORED, 1}));
    }

    @Test
    void the_bootstrap_gives_the_same_interval_twice_with_the_same_seed() {
        double[] values = {1_000, 1_200, 1_250, 1_400, 1_900, 2_300, 2_350, 4_000, 9_000, 9_100};

        double[] first = Statistics.medianInterval(values, 10_000, 20261115);
        double[] second = Statistics.medianInterval(values, 10_000, 20261115);

        assertArrayEquals(first, second);
        assertTrue(first[0] <= 2_100 && 2_100 <= first[1], first[0] + " " + first[1]);
        assertTrue(first[0] >= 1_000 && first[1] <= 9_100);
        assertTrue(first[0] < first[1]);
    }

    @Test
    void the_bootstrap_does_not_depend_on_the_order_of_the_values() {
        double[] ordered = {1, 2, 3, 4, 5, 6, 7};
        double[] shuffled = {5, 1, 7, 3, 2, 6, 4};

        assertArrayEquals(Statistics.medianInterval(ordered, 2_000, 7), Statistics.medianInterval(shuffled, 2_000, 7));
    }

    @Test
    void the_bootstrap_interval_of_identical_values_is_that_value() {
        assertArrayEquals(new double[]{42, 42}, Statistics.medianInterval(new double[]{42, 42, 42, 42}, 1_000, 1));
    }

    @Test
    void a_bootstrap_bound_that_lands_on_a_censored_value_is_censored() {
        double[] interval = Statistics.medianInterval(new double[]{100, CENSORED, CENSORED, CENSORED}, 1_000, 1);

        assertEquals(CENSORED, interval[1]);
    }

    @Test
    void the_bootstrap_of_a_difference_of_medians_gives_the_same_interval_twice_and_contains_a_clear_difference() {
        double[] slow = {5_000, 5_100, 5_200, 5_300, 5_400};
        double[] fast = {1_000, 1_100, 1_200, 1_300, 1_400};

        double[] first = Statistics.medianDifferenceInterval(slow, fast, 5_000, 99);
        double[] second = Statistics.medianDifferenceInterval(slow, fast, 5_000, 99);

        assertArrayEquals(first, second);
        assertTrue(first[0] >= 5_000 - 1_400 && first[1] <= 5_400 - 1_000, first[0] + " " + first[1]);
        assertTrue(first[0] <= 4_000 && 4_000 <= first[1]);
    }

    @Test
    void the_seed_is_a_stable_combination_of_the_stored_seed_with_approach_and_column() {
        long seed = Statistics.seed(20261115, "way-a", "P1");

        assertEquals(seed, Statistics.seed(20261115, "way-a", "P1"));
        assertNotEquals(seed, Statistics.seed(20261115, "way-b", "P1"));
        assertNotEquals(seed, Statistics.seed(20261115, "way-a", "P2"));
        assertNotEquals(seed, Statistics.seed(20261116, "way-a", "P1"));
        assertNotEquals(Statistics.seed(1, "ab", "c"), Statistics.seed(1, "a", "bc"));
        assertEquals(31 * (31 * 5L + 'x') + '/', Statistics.seed(5, "x"));
    }

    private static void assertFalseFinite(double value) {
        assertTrue(!Double.isFinite(value), Double.toString(value));
    }
}
