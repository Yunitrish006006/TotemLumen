package dev.totem.lumen.gameplay.light;

import net.minecraft.server.level.ServerLevel;

/** Local authoritative-light rebuild volume for one changed 16^3 anchor section. */
record LightRebuildRegion(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {
    static final int MAX_PROPAGATION_DISTANCE = PackedRgbLight.MAX_CHANNEL;

    static LightRebuildRegion around(ServerLevel level, ServerSectionKey anchor) {
        int minY = Math.max(level.getMinY(), anchor.minBlockY() - MAX_PROPAGATION_DISTANCE);
        int maxY = Math.min(level.getMaxY() - 1, anchor.maxBlockY() + MAX_PROPAGATION_DISTANCE);
        return new LightRebuildRegion(
                anchor.minBlockX() - MAX_PROPAGATION_DISTANCE,
                minY,
                anchor.minBlockZ() - MAX_PROPAGATION_DISTANCE,
                anchor.maxBlockX() + MAX_PROPAGATION_DISTANCE,
                maxY,
                anchor.maxBlockZ() + MAX_PROPAGATION_DISTANCE
        );
    }

    int sizeX() { return maxX - minX + 1; }
    int sizeY() { return maxY - minY + 1; }
    int sizeZ() { return maxZ - minZ + 1; }
    long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    int minSectionX() { return Math.floorDiv(minX, 16); }
    int maxSectionX() { return Math.floorDiv(maxX, 16); }
    int minSectionY() { return Math.floorDiv(minY, 16); }
    int maxSectionY() { return Math.floorDiv(maxY, 16); }
    int minSectionZ() { return Math.floorDiv(minZ, 16); }
    int maxSectionZ() { return Math.floorDiv(maxZ, 16); }
}
