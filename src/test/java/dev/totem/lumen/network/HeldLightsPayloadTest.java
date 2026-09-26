package dev.totem.lumen.network;

import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HeldLightsPayloadTest {
    @Test
    void snapshotIsBoundedAndImmutable() {
        Identifier dimension = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        HeldLightsPayload payload = new HeldLightsPayload(
                dimension, List.of(new HeldLightsPayload.Source(42, (char) 0xE59F, InteractionHand.MAIN_HAND))
        );
        assertEquals(dimension, payload.dimension());
        assertEquals(42, payload.sources().getFirst().entityId());
        assertEquals(InteractionHand.MAIN_HAND, payload.sources().getFirst().hand());
        assertThrows(UnsupportedOperationException.class,
                () -> payload.sources().add(new HeldLightsPayload.Source(43, (char) 1, InteractionHand.OFF_HAND)));
        assertThrows(IllegalArgumentException.class,
                () -> new HeldLightsPayload(dimension, java.util.Collections.nCopies(
                        HeldLightsPayload.MAX_SOURCES + 1,
                        new HeldLightsPayload.Source(42, (char) 1, InteractionHand.MAIN_HAND)
                )));
        assertThrows(IllegalArgumentException.class,
                () -> new HeldLightsPayload.Source(42, (char) 0, InteractionHand.MAIN_HAND));
        assertThrows(IllegalArgumentException.class,
                () -> new HeldLightsPayload.Source(42, (char) 1, null));
    }
}
