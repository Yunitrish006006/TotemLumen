package dev.totem.lumen.integration;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.geometry.BlockModelMeshRegistry;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Arrays;

/**
 * Extracts a compact, immutable alpha silhouette from the sprite used by one P14C model quad.
 *
 * <p>The Vulkan renderer deliberately does not retain Minecraft atlas/sprite objects. A 32x32
 * one-bit mask is enough to represent vanilla 16x16 cutout textures exactly while keeping the
 * shared scene tail small. Animated sprites use the union of their unique frames: a texel is kept
 * when it is opaque in any frame, preventing animation timing from making ray geometry flicker.</p>
 */
final class P14AlphaCutoutMask {
    private static boolean captureFailureLogged;

    private P14AlphaCutoutMask() {
    }

    static Capture capture(MutableQuadView quad) {
        float[] uvs = new float[BlockModelMeshRegistry.UV_FLOATS_PER_QUAD];
        int[] maskWords = opaqueMask();

        try {
            TextureAtlas atlas = Minecraft.getInstance()
                    .getAtlasManager()
                    .getAtlasOrThrow(quad.atlas().getId());
            TextureAtlasSprite sprite = ((FabricTextureAtlas) atlas).spriteFinder().find(quad);
            if (sprite == null) return new Capture(uvs, maskWords, false);

            float uSpan = sprite.getU1() - sprite.getU0();
            float vSpan = sprite.getV1() - sprite.getV0();
            if (Math.abs(uSpan) < 0.0000001f || Math.abs(vSpan) < 0.0000001f) {
                return new Capture(uvs, maskWords, false);
            }

            for (int vertex = 0; vertex < 4; vertex++) {
                uvs[vertex * 2] = clamp01((quad.u(vertex) - sprite.getU0()) / uSpan);
                uvs[vertex * 2 + 1] = clamp01((quad.v(vertex) - sprite.getV0()) / vSpan);
            }

            SpriteContents contents = sprite.contents();
            int width = Math.max(1, contents.width());
            int height = Math.max(1, contents.height());
            int[] frames = contents.getUniqueFrames().toArray();
            if (frames.length == 0) frames = new int[]{0};

            Arrays.fill(maskWords, 0);
            boolean hasTransparentTexel = false;
            int resolution = BlockModelMeshRegistry.ALPHA_MASK_RESOLUTION;
            for (int y = 0; y < resolution; y++) {
                int sourceY = Math.min(
                        height - 1,
                        (int) Math.floor(((y + 0.5) / resolution) * height)
                );
                for (int x = 0; x < resolution; x++) {
                    int sourceX = Math.min(
                            width - 1,
                            (int) Math.floor(((x + 0.5) / resolution) * width)
                    );

                    boolean opaque = false;
                    for (int frame : frames) {
                        if (!contents.isTransparent(frame, sourceX, sourceY)) {
                            opaque = true;
                            break;
                        }
                    }

                    int bit = y * resolution + x;
                    if (opaque) {
                        maskWords[bit >>> 5] |= 1 << (bit & 31);
                    } else {
                        hasTransparentTexel = true;
                    }
                }
            }

            if (!hasTransparentTexel) {
                Arrays.fill(maskWords, -1);
            }
            return new Capture(uvs, maskWords, hasTransparentTexel);
        } catch (Throwable failure) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                TotemLumenClient.LOGGER.warn(
                        "P14E alpha-cutout sprite extraction failed; affected quads remain conservatively opaque",
                        failure
                );
            }
            return new Capture(uvs, maskWords, false);
        }
    }

    static int[] opaqueMask() {
        int[] words = new int[BlockModelMeshRegistry.ALPHA_MASK_WORDS_PER_QUAD];
        Arrays.fill(words, -1);
        return words;
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    record Capture(float[] uvs, int[] maskWords, boolean cutout) {
    }
}
