package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbPropagationQueueTest {
    @Test
    void preservesFifoOrderAndPackedValuesAcrossGrowth() {
        RgbPropagationQueue queue = new RgbPropagationQueue();

        for (int index = 0; index < 5000; index++) {
            queue.add(index, index & 0xFFFF);
        }

        assertFalse(queue.isEmpty());
        for (int index = 0; index < 5000; index++) {
            assertEquals(index, queue.remove());
            assertEquals(index & 0xFFFF, queue.packed());
        }
        assertTrue(queue.isEmpty());
    }
}
