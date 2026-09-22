package dev.totem.lumen.gpu;

import dev.totem.lumen.material.PbrAnimation;
import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
import dev.totem.lumen.material.PbrTextureRuntimeProperties;
import dev.totem.lumen.material.PbrTextureHandleRegistry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GpuPbrTextureSceneTest {
    @Test
    void preservesAlbedoAlphaAndAuxiliaryMapFlags() {
        String sprite = "minecraft:block/test_alpha";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);
        assertTrue(handle > 0);

        PbrImage albedo = new PbrImage(
                2, 1,
                new int[]{0x00112233, 0xFF445566}
        );
        PbrImage normal = new PbrImage(
                2, 1,
                new int[]{0x008080FF, 0x008080FF}
        );
        PbrImage specular = new PbrImage(
                2, 1,
                new int[]{0xFF804000, 0xFF804000}
        );

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrTextureScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        var result = GpuPbrTextureScene.pack(
                buffer,
                0,
                List.of(new PbrTextureData(sprite, albedo, normal, specular))
        );

        assertEquals(1, result.textureCount());
        assertEquals(2, result.texelCount());
        assertEquals(1, result.alphaTextures());

        int descriptor = GpuPbrTextureScene.DESCRIPTOR_BASE_WORD
                + handle * GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD;
        int flags = buffer.getInt((descriptor + 3) * Integer.BYTES);
        assertNotEquals(0, flags & GpuPbrTextureScene.FLAG_HAS_ALPHA);
        assertNotEquals(0, flags & GpuPbrTextureScene.FLAG_HAS_NORMAL);
        assertNotEquals(0, flags & GpuPbrTextureScene.FLAG_HAS_SPECULAR);

        int texelOffset = buffer.getInt(descriptor * Integer.BYTES);
        int texelBase = GpuPbrTextureScene.TEXEL_POOL_BASE_WORD
                + texelOffset * GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD;
        assertEquals(0x00112233, buffer.getInt(texelBase * Integer.BYTES));
        assertEquals(0xFF445566, buffer.getInt(
                (texelBase + GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD) * Integer.BYTES
        ));
    }

    @Test
    void packsAnimatedFramesAndTimelineWithoutPerTickReupload() {
        String sprite = "minecraft:block/animated_test";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);
        assertTrue(handle > 0);

        PbrImage albedo = new PbrImage(
                1,
                2,
                new int[]{0xFF112233, 0xFF445566}
        );
        PbrAnimation albedoAnimation = new PbrAnimation(
                1,
                1,
                new int[]{0, 0, 1},
                false
        );

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrTextureScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        var result = GpuPbrTextureScene.pack(
                buffer,
                0,
                List.of(new PbrTextureData(
                        sprite,
                        albedo,
                        null,
                        null,
                        albedoAnimation,
                        null,
                        null
                ))
        );

        assertEquals(1, result.animatedTextures());
        assertEquals(3, result.animationTimelineWords());
        assertEquals(2, result.texelCount());

        int descriptor = GpuPbrTextureScene.DESCRIPTOR_BASE_WORD
                + handle * GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD;
        int flags = buffer.getInt((descriptor + 3) * Integer.BYTES);
        assertNotEquals(0, flags & GpuPbrTextureScene.FLAG_ANIMATED);
        assertEquals(2, buffer.getInt((descriptor + 8) * Integer.BYTES));
        int timelineOffset = buffer.getInt((descriptor + 9) * Integer.BYTES);
        int timelineLength = buffer.getInt((descriptor + 10) * Integer.BYTES);
        assertEquals(3, timelineLength);

        int timelineBase = GpuPbrTextureScene.ANIMATION_POOL_BASE_WORD + timelineOffset;
        assertEquals(0, buffer.getInt(timelineBase * Integer.BYTES));
        assertEquals(0, buffer.getInt((timelineBase + 1) * Integer.BYTES));
        assertEquals(1, buffer.getInt((timelineBase + 2) * Integer.BYTES));

        int texelOffset = buffer.getInt(descriptor * Integer.BYTES);
        int texelBase = GpuPbrTextureScene.TEXEL_POOL_BASE_WORD
                + texelOffset * GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD;
        assertEquals(0xFF112233, buffer.getInt(texelBase * Integer.BYTES));
        assertEquals(
                0xFF445566,
                buffer.getInt(
                        (texelBase + GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD)
                                * Integer.BYTES
                )
        );
    }

    @Test
    void combinesIndependentSpecularAnimationWithStaticAlbedo() {
        String sprite = "minecraft:block/emissive_animation_test";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);

        PbrImage albedo = new PbrImage(1, 1, new int[]{0xFF808080});
        PbrImage specular = new PbrImage(
                1,
                2,
                new int[]{0x00800000, 0xFE800000}
        );
        PbrAnimation specularAnimation = new PbrAnimation(
                1,
                1,
                new int[]{0, 1},
                false
        );

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrTextureScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        var result = GpuPbrTextureScene.pack(
                buffer,
                0,
                List.of(new PbrTextureData(
                        sprite,
                        albedo,
                        null,
                        specular,
                        null,
                        null,
                        specularAnimation
                ))
        );

        assertEquals(1, result.animatedTextures());
        assertEquals(2, result.texelCount());

        int descriptor = GpuPbrTextureScene.DESCRIPTOR_BASE_WORD
                + handle * GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD;
        int texelOffset = buffer.getInt(descriptor * Integer.BYTES);
        int frame0 = GpuPbrTextureScene.TEXEL_POOL_BASE_WORD
                + texelOffset * GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD;
        int frame1 = frame0 + GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD;

        assertEquals(0x00800000, buffer.getInt((frame0 + 2) * Integer.BYTES));
        assertEquals(0xFE800000, buffer.getInt((frame1 + 2) * Integer.BYTES));
        assertEquals(0xFF808080, buffer.getInt(frame0 * Integer.BYTES));
        assertEquals(0xFF808080, buffer.getInt(frame1 * Integer.BYTES));
    }

    @Test
    void packsStableRuntimeMaterialScalarsWithoutTextureSpecificShaderFlags() {
        String sprite = "minecraft:block/runtime_rule_test";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);
        PbrImage still = new PbrImage(1, 1, new int[]{0xFFFFFFFF});
        PbrTextureRuntimeProperties properties = new PbrTextureRuntimeProperties(
                0.0f,
                1.75f,
                0.65f,
                1.40f,
                0.50f,
                1.25f
        );

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrTextureScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        GpuPbrTextureScene.pack(
                buffer,
                0,
                List.of(new PbrTextureData(
                        sprite,
                        still,
                        null,
                        null,
                        null,
                        null,
                        null,
                        properties
                ))
        );

        int descriptor = GpuPbrTextureScene.DESCRIPTOR_BASE_WORD
                + handle * GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD;

        assertEquals(24, GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD);
        assertEquals(0.0f, buffer.getFloat((descriptor + 11) * Integer.BYTES));
        assertEquals(1.75f, buffer.getFloat((descriptor + 12) * Integer.BYTES));
        assertEquals(0.65f, buffer.getFloat((descriptor + 13) * Integer.BYTES));
        assertEquals(1.40f, buffer.getFloat((descriptor + 14) * Integer.BYTES));
        assertEquals(0.50f, buffer.getFloat((descriptor + 15) * Integer.BYTES));
        assertEquals(1.25f, buffer.getFloat((descriptor + 16) * Integer.BYTES));
        for (int word = 17; word < GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD; word++) {
            assertEquals(0, buffer.getInt((descriptor + word) * Integer.BYTES));
        }
    }

    @Test
    void boundsLargeTexturesWithoutChangingAspectClass() {
        var dims = GpuPbrTextureScene.boundedDimensions(512, 256);
        assertEquals(128, dims.width());
        assertEquals(64, dims.height());

        var tall = GpuPbrTextureScene.boundedDimensions(32, 256);
        assertEquals(16, tall.width());
        assertEquals(128, tall.height());
    }

    @Test
    void missingAuxiliaryMapsRemainFlaggedAsMissing() {
        String sprite = "minecraft:block/albedo_only";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);
        PbrImage albedo = new PbrImage(1, 1, new int[]{0xFFFFFFFF});

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrTextureScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        GpuPbrTextureScene.pack(
                buffer,
                0,
                List.of(new PbrTextureData(sprite, albedo, null, null))
        );

        int descriptor = GpuPbrTextureScene.DESCRIPTOR_BASE_WORD
                + handle * GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD;
        int flags = buffer.getInt((descriptor + 3) * Integer.BYTES);
        assertEquals(0, flags & GpuPbrTextureScene.FLAG_HAS_NORMAL);
        assertEquals(0, flags & GpuPbrTextureScene.FLAG_HAS_SPECULAR);
    }
}
