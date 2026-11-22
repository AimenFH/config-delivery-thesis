package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigRulesTest {

    private static int effective(String timeoutMs) {
        return new ConfigRules(timeoutMs, "v1").effectiveTimeoutMs();
    }

    @Test
    void a_valid_timeoutMs_is_used_as_it_is() {
        assertEquals(100, effective("100"));
        assertEquals(2500, effective(" 2500 "));
        assertEquals(30000, effective("30000"));
    }

    @Test
    void a_timeoutMs_outside_the_limits_falls_back_to_1000() {
        assertEquals(1000, effective("99"));
        assertEquals(1000, effective("30001"));
    }

    @Test
    void a_timeoutMs_that_is_not_a_number_falls_back_to_1000() {
        assertEquals(1000, effective("abc"));
        assertEquals(1000, effective(""));
    }
}
