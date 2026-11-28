package dev.totem.lumen.integration;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

/** Encodes the surface response above the native block-light byte in Lumia terrain meshes. */
public final class LumiaTerrainMaterials {
    private static volatile ConcurrentHashMap<Block, Integer> metalTags = new ConcurrentHashMap<>();

    private LumiaTerrainMaterials() {}

    static void invalidate() {
        metalTags = new ConcurrentHashMap<>();
    }

    public static void tagTerrain(MutableQuadView quad, BlockState state) {
        int glass = LumiaGlassMaterials.id(state);
        int tag;
        if (glass >= 0) {
            tag = LumiaGlassMaterials.terrainTag(glass);
            quad.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
        } else {
            tag = metalTags.computeIfAbsent(state.getBlock(), LumiaTerrainMaterials::metalTag);
            if (tag == 0) return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.lightmap(vertex, quad.lightmap(vertex) | tag);
        }
    }

    private static int metalTag(Block block) {
        // These three material properties are block-wide, including resource pack overrides.
        var material = MinecraftMaterialResolver.resolve(block.defaultBlockState());
        float response = material.metallic() * (1f - material.roughness()) * material.reflectionScale();
        // Bit 8 identifies glass. Bits 9..14 carry a metal response on other surfaces.
        return Math.round(Math.clamp(response, 0f, 1f) * 63f) << 9;
    }
}
