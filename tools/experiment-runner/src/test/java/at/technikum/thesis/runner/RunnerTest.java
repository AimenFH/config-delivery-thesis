package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    @Test
    void the_evaluate_argument_is_parsed_and_handed_to_the_place_that_is_wired_in_phase_3() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> Runner.main(new String[]{"--evaluate", "results/raw/20261115-200000_s1"}));

        assertEquals("wired in phase 3", thrown.getMessage());
    }

    @Test
    void the_campaign_argument_reads_the_named_campaign_file() {
        IOException thrown = assertThrows(IOException.class,
                () -> Runner.main(new String[]{"--campaign", "campaigns/no-such-campaign.json"}));

        assertTrue(thrown.getMessage().contains("no-such-campaign.json"), thrown.getMessage());
    }

    @Test
    void judge_store_and_sampler_come_from_the_same_place_until_phase_3() throws Exception {
        CampaignConfig.Loaded loaded = Fakes.World.loaded("s1-valid-change", 1, 1, 1);

        assertEquals("wired in phase 3",
                assertThrows(UnsupportedOperationException.class, () -> Runner.Wiring.judge(loaded)).getMessage());
        assertEquals("wired in phase 3", assertThrows(UnsupportedOperationException.class,
                () -> Runner.Wiring.store(loaded, Path.of("results/raw/x"))).getMessage());
        assertEquals("wired in phase 3",
                assertThrows(UnsupportedOperationException.class, () -> Runner.Wiring.sampler(loaded)).getMessage());
    }
}
