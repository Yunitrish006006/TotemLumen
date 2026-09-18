package dev.totem.lumen.material;

import java.util.Arrays;

/** Immutable ARGB image owned by Totem Lumen after resource-pack decode. */
public final class PbrImage {
    private final int width;
    private final int height;
    private final int[] argb;

    public PbrImage(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (argb == null || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("ARGB payload does not match image dimensions");
        }
        this.width = width;
        this.height = height;
        this.argb = argb.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int pixelArgb(int x, int y) {
        return argb[Math.floorMod(y, height) * width + Math.floorMod(x, width)];
    }

    public int sampleNearest(float u, float v) {
        if (!Float.isFinite(u) || !Float.isFinite(v)) {
            throw new IllegalArgumentException("UV must be finite");
        }
        float wrappedU = u - (float) Math.floor(u);
        float wrappedV = v - (float) Math.floor(v);
        int x = Math.min(width - 1, (int) (wrappedU * width));
        int y = Math.min(height - 1, (int) (wrappedV * height));
        return pixelArgb(x, y);
    }

    public int[] copyArgb() {
        return argb.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PbrImage image)) return false;
        return width == image.width
                && height == image.height
                && Arrays.equals(argb, image.argb);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(argb);
    }
}
