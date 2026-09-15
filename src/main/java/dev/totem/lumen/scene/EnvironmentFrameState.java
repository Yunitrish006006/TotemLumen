package dev.totem.lumen.scene;

/**
 * Packs the small amount of per-frame environment state needed by the validation compute shader
 * into the low 32 bits of {@link FrameSnapshot#frameIndex()} without changing the GPU header ABI.
 *
 * <p>The high 32 bits retain the monotonically increasing extraction sequence used on the CPU.
 * The GPU-visible low word uses:</p>
 * <ul>
 *     <li>bits 31..30: dimension family (overworld/nether/end/other)</li>
 *     <li>bits 29..16: normalized 0..23999 day phase</li>
 *     <li>bits 15..0: per-frame stochastic seed</li>
 * </ul>
 */
public final class EnvironmentFrameState {
    public static final int DIMENSION_OVERWORLD = 0;
    public static final int DIMENSION_NETHER = 1;
    public static final int DIMENSION_END = 2;
    public static final int DIMENSION_OTHER = 3;

    private static final int DAY_PHASE_MASK = 0x3FFF;
    private static final int FRAME_SEED_MASK = 0xFFFF;

    private static volatile CapturedEnvironment latest = new CapturedEnvironment("", 0);

    private EnvironmentFrameState() {
    }

    public static void capture(String dimensionId, long dayTime) {
        latest = new CapturedEnvironment(dimensionId, dayPhase14(dayTime));
    }

    public static long packFrameIndex(long extractionSequence, String dimensionId) {
        CapturedEnvironment captured = latest;
        int phase = captured.dimensionId().equals(dimensionId) ? captured.dayPhase14() : 0;
        int gpuWord = (dimensionCode(dimensionId) << 30)
                | ((phase & DAY_PHASE_MASK) << 16)
                | ((int) extractionSequence & FRAME_SEED_MASK);
        return (extractionSequence << 32) | Integer.toUnsignedLong(gpuWord);
    }

    static int dimensionCode(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> DIMENSION_OVERWORLD;
            case "minecraft:the_nether" -> DIMENSION_NETHER;
            case "minecraft:the_end" -> DIMENSION_END;
            default -> DIMENSION_OTHER;
        };
    }

    static int dayPhase14(long dayTime) {
        long timeOfDay = Math.floorMod(dayTime, 24_000L);
        return (int) ((timeOfDay * 16_384L) / 24_000L) & DAY_PHASE_MASK;
    }

    static int gpuWord(long packedFrameIndex) {
        return (int) packedFrameIndex;
    }

    static int gpuDimensionCode(long packedFrameIndex) {
        return gpuWord(packedFrameIndex) >>> 30;
    }

    static int gpuDayPhase14(long packedFrameIndex) {
        return (gpuWord(packedFrameIndex) >>> 16) & DAY_PHASE_MASK;
    }

    static int gpuFrameSeed(long packedFrameIndex) {
        return gpuWord(packedFrameIndex) & FRAME_SEED_MASK;
    }

    private record CapturedEnvironment(String dimensionId, int dayPhase14) {
    }
}
