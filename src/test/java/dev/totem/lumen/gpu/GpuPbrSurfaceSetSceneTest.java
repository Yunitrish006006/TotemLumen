package dev.totem.lumen.gpu;

import dev.totem.lumen.geometry.BlockSurfaceSetRegistry;
import dev.totem.lumen.geometry.QuadSurface;
import dev.totem.lumen.material.PbrTextureHandleRegistry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class GpuPbrSurfaceSetSceneTest {
    @Test
    void packsFallbackOpticsTextureHandleAndCanonicalUvs() {
        String sprite = "minecraft:block/p18_cube_face";
        int handle = PbrTextureHandleRegistry.handleFor(sprite);
        assertTrue(handle > 0);

        QuadSurface face = new QuadSurface(
                sprite,
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        );
        var set = new BlockSurfaceSetRegistry.CubeSurfaceSet(
                face, face, face, face, face, face,
                0.72f,
                0.18f
        );
        int id = BlockSurfaceSetRegistry.register(set);
        assertTrue(id > 0);

        ByteBuffer buffer = ByteBuffer
                .allocate((int) GpuPbrSurfaceSetScene.MAX_STORAGE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        var result = GpuPbrSurfaceSetScene.pack(
                buffer,
                0,
                BlockSurfaceSetRegistry.snapshot()
        );

        assertTrue(result.surfaceSetCount() >= 1);
        int record = GpuPbrSurfaceSetScene.RECORD_BASE_WORD
                + id * GpuPbrSurfaceSetScene.RECORD_WORDS;
        assertEquals(0.72f, Float.intBitsToFloat(buffer.getInt(record * Integer.BYTES)), 0.00001f);
        assertEquals(
                0.18f,
                Float.intBitsToFloat(buffer.getInt((record + 1) * Integer.BYTES)),
                0.00001f
        );

        int firstFace = record + 2;
        assertEquals(handle, buffer.getInt(firstFace * Integer.BYTES));
        assertEquals(
                0.0f,
                Float.intBitsToFloat(buffer.getInt((firstFace + 1) * Integer.BYTES)),
                0.00001f
        );
        assertEquals(
                1.0f,
                Float.intBitsToFloat(buffer.getInt((firstFace + 3) * Integer.BYTES)),
                0.00001f
        );
        assertEquals(
                1.0f,
                Float.intBitsToFloat(buffer.getInt((firstFace + 6) * Integer.BYTES)),
                0.00001f
        );
    }
}
