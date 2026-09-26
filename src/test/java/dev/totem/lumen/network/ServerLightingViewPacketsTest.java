package dev.totem.lumen.network;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ServerLightingViewPacketsTest {
    @Test
    void summaryAndRequestsAreBounded() {
        var summary = new ServerLightingViewPackets.Summary(1, 0, 20_000,
                2_000_000L, true, 100, 2, 3, 2,
                List.of(new ServerLightingViewPackets.PackInfo("vanilla", "Vanilla", false, 1, 1),
                        new ServerLightingViewPackets.PackInfo("totem-lumen:default_lighting", "Default",
                                true, 2, 2)), 2, 2);
        assertEquals(100, summary.blockCount());
        assertEquals(2, summary.enabledPacks().size());
        assertThrows(UnsupportedOperationException.class, () -> summary.enabledPacks().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.Summary(1, 0, 0, 2_000_000L,
                        true, 100, 2, 3, 0, List.of(), 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.PackInfo("test", "Test", true, Float.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.PageRequest(1, 0, 342, "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.PageRequest(1, 0, 0, "x".repeat(65), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.PageRequest(1, 0, 0, "", "x".repeat(257)));
    }

    @Test
    void pagesCopyEntriesAndRejectOversizeOrInvalidColors() {
        var entry = new ServerLightingViewPackets.Entry(
                Identifier.fromNamespaceAndPath("minecraft", "redstone_ore"),
                Identifier.fromNamespaceAndPath("minecraft", "redstone_ore"),
                0, 1.0f, 0.18f, 0.06f, 0, 0, 0, -1, false, "");
        var page = new ServerLightingViewPackets.Page(1, 0, 0, "",
                "totem-lumen:default_lighting", 1, List.of(entry));
        assertEquals(entry, page.entries().getFirst());
        assertEquals("totem-lumen:default_lighting", page.packId());
        assertThrows(UnsupportedOperationException.class, () -> page.entries().add(entry));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.Page(1, 0, 0, "", "", 25,
                        Collections.nCopies(25, entry)));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerLightingViewPackets.Entry(entry.id(), entry.iconItemId(), 0,
                        Float.NaN, 0, 0, 0, 0, 0, -1, false, ""));
    }
}
