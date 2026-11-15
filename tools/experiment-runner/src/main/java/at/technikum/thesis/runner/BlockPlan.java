package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class BlockPlan {

    private BlockPlan() {
    }

    public static List<List<String>> draw(List<String> approaches, int blocks, long seed) {
        Random random = new Random(seed);
        List<List<String>> order = new ArrayList<>();
        for (int block = 0; block < blocks; block++) {
            List<String> shuffled = new ArrayList<>(approaches);
            Collections.shuffle(shuffled, random);
            order.add(List.copyOf(shuffled));
        }
        return List.copyOf(order);
    }
}
