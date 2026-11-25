package dev.totem.lumen.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class NetherScanOrderTest {
    @Test
    void visitsEveryLayerOnceStartingAtThePlayersHeight() {
        assertArrayEquals(new int[]{3, 2, 4, 1, 5, 0},
                ClientGameplayLightPredictor.nearestFirst(6, 3));
        assertArrayEquals(new int[]{0, 1, 2, 3},
                ClientGameplayLightPredictor.nearestFirst(4, -2));
        assertArrayEquals(new int[]{3, 2, 1, 0},
                ClientGameplayLightPredictor.nearestFirst(4, 9));
    }
}
