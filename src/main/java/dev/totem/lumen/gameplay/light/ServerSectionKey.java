package dev.totem.lumen.gameplay.light;

import net.minecraft.core.BlockPos;

/** Compact section coordinate key for one ServerLevel-owned gameplay light engine. */
public record ServerSectionKey(int x, int y, int z) {
    public static ServerSectionKey fromBlock(BlockPos pos) {
        return fromBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    public static ServerSectionKey fromBlock(int x, int y, int z) {
        return new ServerSectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
    }

    public int minBlockX() { return x << 4; }
    public int minBlockY() { return y << 4; }
    public int minBlockZ() { return z << 4; }
    public int maxBlockX() { return minBlockX() + 15; }
    public int maxBlockY() { return minBlockY() + 15; }
    public int maxBlockZ() { return minBlockZ() + 15; }
}
