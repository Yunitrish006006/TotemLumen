package dev.totem.lumen.gameplay.light;

import net.minecraft.core.BlockPos;

/** Immutable server-thread-only block coordinate used by the sparse emissive-source index. */
public record ServerBlockKey(int x, int y, int z) {
    public static ServerBlockKey from(BlockPos pos) {
        return new ServerBlockKey(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }
}
