package dev.totem.lumen.gpu;

import dev.totem.lumen.geometry.BlockSurfaceSetRegistry;
import dev.totem.lumen.geometry.QuadSurface;
import dev.totem.lumen.material.PbrTextureHandleRegistry;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Fixed-index P18 surface table for canonical full-cube AABB hits. */
public final class GpuPbrSurfaceSetScene {
    public static final int ABI_VERSION = 1;
    public static final int MAX_SURFACE_SETS = BlockSurfaceSetRegistry.MAX_SURFACE_SET_ID + 1;
    public static final int FACE_COUNT = 6;
    public static final int FACE_WORDS = 9; // texture handle + four (u,v) pairs
    public static final int RECORD_WORDS = 2 + FACE_COUNT * FACE_WORDS;
    public static final int HEADER_WORDS = 8;
    public static final int RECORD_BASE_WORD = HEADER_WORDS;
    public static final int RECORD_POOL_WORDS = MAX_SURFACE_SETS * RECORD_WORDS;
    public static final int MAX_STORAGE_WORDS = RECORD_BASE_WORD + RECORD_POOL_WORDS;
    public static final long MAX_STORAGE_BYTES = (long) MAX_STORAGE_WORDS * Integer.BYTES;

    private GpuPbrSurfaceSetScene() {
    }

    public static PackResult pack(
            ByteBuffer buffer,
            int baseWord,
            BlockSurfaceSetRegistry.Snapshot snapshot
    ) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(snapshot, "snapshot");
        if (baseWord < 0) throw new IllegalArgumentException("baseWord must be >= 0");

        long required = ((long) baseWord + MAX_STORAGE_WORDS) * Integer.BYTES;
        if (required > buffer.capacity()) {
            throw new IllegalStateException(
                    "P18 cube-surface storage exceeds scene buffer: required="
                            + required + ", capacity=" + buffer.capacity()
            );
        }

        for (int word = 0; word < MAX_STORAGE_WORDS; word++) {
            putWord(buffer, baseWord + word, 0);
        }

        int texturedFaces = 0;
        for (BlockSurfaceSetRegistry.SurfaceSetEntry entry : snapshot.entries()) {
            int id = entry.id();
            if (id <= 0 || id >= MAX_SURFACE_SETS) continue;

            var set = entry.set();
            int record = baseWord + RECORD_BASE_WORD + id * RECORD_WORDS;
            putWord(buffer, record, Float.floatToRawIntBits(set.fallbackRoughness()));
            putWord(buffer, record + 1, Float.floatToRawIntBits(set.fallbackMetallic()));

            for (int face = 0; face < FACE_COUNT; face++) {
                QuadSurface surface = set.face(face);
                int faceWord = record + 2 + face * FACE_WORDS;
                int handle = surface.textured()
                        ? PbrTextureHandleRegistry.handleFor(surface.spriteId())
                        : 0;
                if (handle < 0) handle = 0;
                putWord(buffer, faceWord, handle);
                if (handle > 0) texturedFaces++;

                for (int vertex = 0; vertex < 4; vertex++) {
                    putWord(
                            buffer,
                            faceWord + 1 + vertex * 2,
                            Float.floatToRawIntBits(surface.u(vertex))
                    );
                    putWord(
                            buffer,
                            faceWord + 2 + vertex * 2,
                            Float.floatToRawIntBits(surface.v(vertex))
                    );
                }
            }
        }

        putWord(buffer, baseWord, ABI_VERSION);
        putWord(buffer, baseWord + 1, snapshot.entries().size());
        putWord(buffer, baseWord + 2, texturedFaces);
        putWord(buffer, baseWord + 3, RECORD_WORDS);
        putWord(buffer, baseWord + 4, RECORD_BASE_WORD);

        return new PackResult(snapshot.entries().size(), texturedFaces, MAX_STORAGE_WORDS);
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(Math.multiplyExact(wordIndex, Integer.BYTES), value);
    }

    public record PackResult(int surfaceSetCount, int texturedFaces, int usedWords) {
        public long usedBytes() {
            return (long) usedWords * Integer.BYTES;
        }
    }
}
