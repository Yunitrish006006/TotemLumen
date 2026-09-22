package dev.totem.lumen.geometry;

/**
 * Minecraft-independent texture identity and sprite-local UVs for one renderer-resolved quad.
 *
 * <p>An empty sprite id means that the capture path did not expose a texture identity. UVs are
 * normalized to the source sprite rather than the stitched atlas so resource-pack reloads and atlas
 * repacking cannot invalidate retained surface coordinates.</p>
 */
public record QuadSurface(
        String spriteId,
        float u0,
        float v0,
        float u1,
        float v1,
        float u2,
        float v2,
        float u3,
        float v3
) {
    public static final QuadSurface UNTEXTURED = new QuadSurface(
            "",
            0.0f, 0.0f,
            0.0f, 0.0f,
            0.0f, 0.0f,
            0.0f, 0.0f
    );

    public QuadSurface {
        if (spriteId == null) {
            throw new IllegalArgumentException("spriteId cannot be null");
        }
        validate(u0);
        validate(v0);
        validate(u1);
        validate(v1);
        validate(u2);
        validate(v2);
        validate(u3);
        validate(v3);
    }

    public boolean textured() {
        return !spriteId.isBlank();
    }

    public float u(int vertex) {
        return switch (vertex) {
            case 0 -> u0;
            case 1 -> u1;
            case 2 -> u2;
            case 3 -> u3;
            default -> throw new IndexOutOfBoundsException("vertex=" + vertex);
        };
    }

    public float v(int vertex) {
        return switch (vertex) {
            case 0 -> v0;
            case 1 -> v1;
            case 2 -> v2;
            case 3 -> v3;
            default -> throw new IndexOutOfBoundsException("vertex=" + vertex);
        };
    }

    private static void validate(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("quad UV must be finite");
        }
    }
}
