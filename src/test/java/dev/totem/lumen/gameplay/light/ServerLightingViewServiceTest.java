package dev.totem.lumen.gameplay.light;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerLightingViewServiceTest {
    @Test
    void candleCakesUseCakeIcon() {
        Identifier cake = BuiltInRegistries.ITEM.getKey(Items.CAKE);
        assertEquals(cake, ServerLightingViewService.blockIcon(
                Identifier.fromNamespaceAndPath("minecraft", "candle_cake")));
        assertEquals(cake, ServerLightingViewService.blockIcon(
                Identifier.fromNamespaceAndPath("minecraft", "red_candle_cake")));
    }

    @Test
    void selectedPackCanAddAnUnconfiguredVanillaLight() {
        Identifier torch = Identifier.fromNamespaceAndPath("minecraft", "torch");
        assertTrue(ServerLightingViewService.blockEntries("file/totem-lumen-lighting-example")
                .stream().anyMatch(entry -> entry.id().equals(torch)
                        && entry.detail().equals("not_in_pack")));
    }
}
