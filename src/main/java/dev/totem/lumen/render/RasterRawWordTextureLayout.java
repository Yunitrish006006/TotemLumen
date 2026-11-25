package dev.totem.lumen.render;

/** Portable RGBA8 byte transport for arbitrary raw 32-bit GPU ABI words. */
public final class RasterRawWordTextureLayout {
    public static final int WIDTH = 4096;

    private RasterRawWordTextureLayout() { }

    public static int rowsForWords(int wordCount) {
        if (wordCount < 0) throw new IllegalArgumentException("wordCount must be >= 0");
        return Math.max(1, Math.addExact(wordCount, WIDTH - 1) / WIDTH);
    }

    public static int x(int wordIndex) {
        if (wordIndex < 0) throw new IllegalArgumentException("wordIndex must be >= 0");
        return wordIndex % WIDTH;
    }

    public static int y(int wordIndex) {
        if (wordIndex < 0) throw new IllegalArgumentException("wordIndex must be >= 0");
        return wordIndex / WIDTH;
    }
}
