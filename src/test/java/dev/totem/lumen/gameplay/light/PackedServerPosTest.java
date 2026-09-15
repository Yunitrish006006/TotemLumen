package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PackedServerPosTest {
    @Test
    void roundTripsVanillaRangeCoordinates() {
        assertRoundTrip(0, 0, 0);
        assertRoundTrip(-1, -64, -1);
        assertRoundTrip(30_000_000, 319, -30_000_000);
        assertRoundTrip(-30_000_000, -2032, 30_000_000);
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = PackedServerPos.pack(x, y, z);
        assertEquals(x, PackedServerPos.x(packed));
        assertEquals(y, PackedServerPos.y(packed));
        assertEquals(z, PackedServerPos.z(packed));
    }
}
