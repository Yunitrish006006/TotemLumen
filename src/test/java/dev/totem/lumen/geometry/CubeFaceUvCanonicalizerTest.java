package dev.totem.lumen.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeFaceUvCanonicalizerTest {
    private static final float EPSILON = 0.0002f;
    private static final QuadSurface SOURCE = new QuadSurface("test:face",
            .1f, .2f, .3f, .4f, .5f, .6f, .7f, .8f, 0x91bd59);

    @Test void everyPermutationAndAxisMatchesThePreviousAlgorithm() {
        int checked = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
                for (int c = 0; c < 4; c++) for (int d = 0; d < 4; d++) {
                    if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
                    float[] positions = face(axis, new int[]{a, b, c, d});
                    QuadSurface actual = CubeFaceUvCanonicalizer.canonicalize(positions, 3, axis, SOURCE, EPSILON);
                    assertEquals(reference(positions, 3, axis), actual);
                    assertEquals(SOURCE.tintRgb(), actual.tintRgb());
                    checked++;
                }
        }
        assertEquals(72, checked);
    }

    @Test void canonicalInputReusesTheImmutableSurface() {
        assertSame(SOURCE, CubeFaceUvCanonicalizer.canonicalize(
                face(0, new int[]{0, 1, 3, 2}), 3, 0, SOURCE, EPSILON));
    }

    @Test void malformedFacesKeepThePreviousFallback() {
        assertSame(SOURCE, CubeFaceUvCanonicalizer.canonicalize(
                face(0, new int[]{0, 1, 1, 2}), 3, 0, SOURCE, EPSILON));
        for (float value : new float[]{.5f, Float.NaN, Float.POSITIVE_INFINITY, -.01f}) {
            float[] positions = face(0, new int[]{0, 1, 3, 2});
            positions[4] = value;
            assertSame(SOURCE, CubeFaceUvCanonicalizer.canonicalize(positions, 3, 0, SOURCE, EPSILON));
        }
    }

    @Test void boundaryToleranceAndUntexturedSurfacesArePreserved() {
        float[] positions = face(0, new int[]{3, 2, 0, 1});
        positions[4] += EPSILON / 2;
        assertEquals(reference(positions, 3, 0),
                CubeFaceUvCanonicalizer.canonicalize(positions, 3, 0, SOURCE, EPSILON));
        assertEquals(QuadSurface.UNTEXTURED, CubeFaceUvCanonicalizer.canonicalize(
                positions, 3, 0, QuadSurface.UNTEXTURED, EPSILON));
    }

    private static float[] face(int axis, int[] corners) {
        float[] positions = new float[15]; // Nonzero base exercises packed face offsets.
        for (int i = 0; i < 4; i++) {
            positions[3 + i * 3 + (axis + 1) % 3] = corners[i] & 1;
            positions[3 + i * 3 + (axis + 2) % 3] = (corners[i] >>> 1) & 1;
        }
        return positions;
    }

    private static QuadSurface reference(float[] positions, int base, int axis) {
        float[] u = new float[4], v = new float[4];
        for (int i = 0; i < 4; i++) {
            int corner = (positions[base + i * 3 + (axis + 1) % 3] > .5f ? 1 : 0)
                    | (positions[base + i * 3 + (axis + 2) % 3] > .5f ? 2 : 0);
            u[corner] = SOURCE.u(i);
            v[corner] = SOURCE.v(i);
        }
        return new QuadSurface(SOURCE.spriteId(), u[0], v[0], u[1], v[1],
                u[3], v[3], u[2], v[2], SOURCE.tintRgb());
    }
}
