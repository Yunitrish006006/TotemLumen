package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CrossCollisionBlock;

import java.util.IdentityHashMap;
import java.util.Map;

/** Stable optical material IDs shared by terrain vertices, the voxel scene and GPU palette. */
public final class LumiaGlassMaterials {
    private static final int COLOURS = 17;
    static final int TABLE_TEXELS = COLOURS * 2 * 2;
    private static final Block[] BLOCKS = new Block[COLOURS * 2];
    private static final Map<Block, Integer> IDS = new IdentityHashMap<>();
    static {
        BLOCKS[0] = Blocks.GLASS;
        BLOCKS[COLOURS] = Blocks.GLASS_PANE;
        for (var dye : DyeColor.values()) {
            int colour = dye.getId() + 1;
            var name = dye.getName() + "_stained_glass";
            BLOCKS[colour] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name));
            BLOCKS[COLOURS + colour] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name + "_pane"));
        }
        for (int i = 0; i < BLOCKS.length; i++) IDS.put(BLOCKS[i], i);
    }

    private LumiaGlassMaterials() {}

    static int id(BlockState state) {
        return IDS.getOrDefault(state.getBlock(), -1);
    }

    static int terrainTag(int material) {
        return 0x100 | (material >= COLOURS ? 0x200 : 0) | ((material % COLOURS) << 10);
    }

    static int voxel(BlockState state, int emission) {
        int material = id(state);
        if (material < 0) return emission;
        int rgb = emission & 0xffffff;
        if (material >= COLOURS && rgb == 0) {
            // Non-emitting panes have room for their four connected arms in the red byte.
            int arms = (state.getValue(CrossCollisionBlock.WEST) ? 1 : 0)
                    | (state.getValue(CrossCollisionBlock.EAST) ? 2 : 0)
                    | (state.getValue(CrossCollisionBlock.NORTH) ? 4 : 0)
                    | (state.getValue(CrossCollisionBlock.SOUTH) ? 8 : 0);
            return ((80 + material - COLOURS) << 24) | arms;
        }
        // Preserve all three source channels, including custom server emission on glass.
        return ((129 + material) << 24) | rgb;
    }

    static void writePalette(NativeImage image, int start) {
        for (int i = 0; i < BLOCKS.length; i++) {
            var mat = MinecraftMaterialResolver.resolve(BLOCKS[i].defaultBlockState());
            image.setPixelABGR(start + i * 2, 0, channel(mat.transmissionR())
                    | (channel(mat.transmissionG()) << 8) | (channel(mat.transmissionB()) << 16)
                    | (channel(mat.opacity()) << 24));
            image.setPixelABGR(start + i * 2 + 1, 0, 0xff000000 | channel((mat.indexOfRefraction() - 1f) / 2f));
        }
    }

    private static int channel(float value) {
        return Math.round(Math.clamp(value, 0f, 1f) * 255);
    }
}
