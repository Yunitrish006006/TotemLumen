package dev.totem.lumen.gpu;

import dev.totem.lumen.material.PbrImage;
import dev.totem.lumen.material.PbrTextureData;
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
