package dev.totem.lumen.geometry;

/** Reorders cube-face UVs without allocating per-face scratch arrays. */
public final class CubeFaceUvCanonicalizer {
    private CubeFaceUvCanonicalizer() { }

    public static QuadSurface canonicalize(float[] positions, int base, int constantAxis,
                                          QuadSurface source, float epsilon) {
        int axisA = (constantAxis + 1) % 3;
        int axisB = (constantAxis + 2) % 3;
        int seen = 0;
        int verticesByCorner = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            float a = positions[base + vertex * 3 + axisA];
            float b = positions[base + vertex * 3 + axisB];
            if (!nearBoundary(a, epsilon) || !nearBoundary(b, epsilon)) return source;
            int corner = (a > 0.5f ? 1 : 0) | (b > 0.5f ? 2 : 0);
            int bit = 1 << corner;
            if ((seen & bit) != 0) return source;
            seen |= bit;
            verticesByCorner |= vertex << (corner * 2);
        }
        if (seen != 15) return source;
        // Canonical winding is 00, 10, 11, 01 (corner indices 0, 1, 3, 2).
        int v0 = verticesByCorner & 3;
        int v1 = (verticesByCorner >>> 2) & 3;
        int v2 = (verticesByCorner >>> 6) & 3;
        int v3 = (verticesByCorner >>> 4) & 3;
        if (v0 == 0 && v1 == 1 && v2 == 2 && v3 == 3) return source;
        return new QuadSurface(source.spriteId(),
                source.u(v0), source.v(v0), source.u(v1), source.v(v1),
                source.u(v2), source.v(v2), source.u(v3), source.v(v3), source.tintRgb());
    }

    private static boolean nearBoundary(float value, float epsilon) {
        return Math.abs(value) <= epsilon || Math.abs(value - 1) <= epsilon;
    }
}
