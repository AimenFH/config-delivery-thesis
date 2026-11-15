package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPlanTest {

    private static final List<String> APPROACHES = List.of("one", "two", "three");

    @Test
    void the_same_seed_gives_the_same_order() {
        assertEquals(BlockPlan.draw(APPROACHES, 25, 20261115L), BlockPlan.draw(APPROACHES, 25, 20261115L));
    }

    @Test
    void another_seed_gives_another_order() {
        assertNotEquals(BlockPlan.draw(APPROACHES, 25, 20261115L), BlockPlan.draw(APPROACHES, 25, 20261116L));
    }

    @Test
    void all_blocks_of_the_campaign_are_drawn_up_front() {
        assertEquals(25, BlockPlan.draw(APPROACHES, 25, 1).size());
        assertEquals(2, BlockPlan.draw(APPROACHES, 2, 1).size());
    }

    @Test
    void every_block_holds_every_approach_once() {
        for (long seed = 0; seed < 50; seed++) {
            for (List<String> block : BlockPlan.draw(APPROACHES, 25, seed)) {
                assertEquals(3, block.size());
                assertEquals(Set.of("one", "two", "three"), new HashSet<>(block));
            }
        }
    }

    @Test
    void the_order_inside_a_block_is_random_and_not_the_order_of_the_file() {
        Set<List<String>> orders = new HashSet<>(BlockPlan.draw(APPROACHES, 25, 20261115L));

        assertTrue(orders.size() > 1, "25 blocks with one single order: " + orders);
    }

    @Test
    void the_blocks_come_from_one_random_generator_started_with_the_seed() {
        Random random = new Random(42);
        List<List<String>> expected = new ArrayList<>();
        for (int block = 0; block < 4; block++) {
            List<String> order = new ArrayList<>(APPROACHES);
            Collections.shuffle(order, random);
            expected.add(order);
        }

        assertEquals(expected, BlockPlan.draw(APPROACHES, 4, 42));
    }

    @Test
    void a_shorter_campaign_with_the_same_seed_starts_with_the_same_blocks() {
        assertEquals(BlockPlan.draw(APPROACHES, 25, 7).subList(0, 2), BlockPlan.draw(APPROACHES, 2, 7));
    }
}
