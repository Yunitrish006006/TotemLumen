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
    void packsTintAtEachFaceWithoutChangingUvsOrAdjacentRecord() {
        QuadSurface[] faces = new QuadSurface[6];
        for (int face = 0; face < 6; face++) {
            faces[face] = new QuadSurface("test:tint_face", 0, 0, 1, 0, 1, 1, 0, 1,
                    face == 0 ? 0 : 0x102030 * face);
        }
        var set = new BlockSurfaceSetRegistry.CubeSurfaceSet(
                faces[0], faces[1], faces[2], faces[3], faces[4], faces[5], 0.8f, 0);
        var snapshot = new BlockSurfaceSetRegistry.Snapshot(1, java.util.List.of(
                new BlockSurfaceSetRegistry.SurfaceSetEntry(1, set),
                new BlockSurfaceSetRegistry.SurfaceSetEntry(BlockSurfaceSetRegistry.MAX_SURFACE_SET_ID, set)));
        int base = 4;
        ByteBuffer buffer = ByteBuffer.allocate((base + GpuPbrSurfaceSetScene.MAX_STORAGE_WORDS + 1) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 0x12345678);
        buffer.putInt(buffer.capacity() - 4, 0x76543210);
        GpuPbrSurfaceSetScene.pack(buffer, base, snapshot);
        assertEquals(2, buffer.getInt(base * 4));
        for (int id : new int[]{1, BlockSurfaceSetRegistry.MAX_SURFACE_SET_ID}) {
            for (int face = 0; face < 6; face++) {
                int word = base + GpuPbrSurfaceSetScene.RECORD_BASE_WORD
                        + id * GpuPbrSurfaceSetScene.RECORD_WORDS + 2
                        + face * GpuPbrSurfaceSetScene.FACE_WORDS;
                assertEquals(faces[face].tintRgb(), buffer.getInt((word + GpuPbrSurfaceSetScene.FACE_TINT_WORD) * 4));
                assertEquals(1.0f, buffer.getFloat((word + 6) * 4));
            }
        }
        assertEquals(0x12345678, buffer.getInt(0));
        assertEquals(0x76543210, buffer.getInt(buffer.capacity() - 4));
    }

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
