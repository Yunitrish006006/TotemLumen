package dev.totem.lumen.scene;

/**
 * Packs the small amount of per-frame environment state needed by the validation compute shader
 * into the low 32 bits of {@link FrameSnapshot#frameIndex()} without changing the GPU header ABI.
 *
 * <p>The high 32 bits retain the monotonically increasing extraction sequence used on the CPU.
 * The GPU-visible low word uses:</p>
 * <ul>
 *     <li>bits 31..30: dimension family (overworld/nether/end/other)</li>
 *     <li>bits 29..19: normalized 0..23999 day phase</li>
 *     <li>bits 18..16: Minecraft 8-step lunar phase</li>
 *     <li>bits 15..0: per-frame stochastic seed</li>
 * </ul>
 *
 * <p>Eleven day-phase bits still resolve the 24,000-tick cycle to about 11.7 ticks per step while
 * preserving the full 16-bit stochastic seed used by temporal GI.</p>
 */
public final class EnvironmentFrameState {
    public static final int DIMENSION_OVERWORLD = 0;
    public static final int DIMENSION_NETHER = 1;
    public static final int DIMENSION_END = 2;
    public static final int DIMENSION_OTHER = 3;

    private static final int DAY_PHASE_MASK = 0x07FF;
    private static final int MOON_PHASE_MASK = 0x7;
    private static final int FRAME_SEED_MASK = 0xFFFF;
    private static final int DAY_PHASE_SHIFT = 19;
    private static final int MOON_PHASE_SHIFT = 16;

    private static volatile CapturedEnvironment latest = new CapturedEnvironment("", 0, 0);

    private EnvironmentFrameState() {
    }

    public static void capture(String dimensionId, long dayTime, int moonPhase) {
        latest = new CapturedEnvironment(
                dimensionId,
                dayPhase11(dayTime),
                moonPhase & MOON_PHASE_MASK
        );
    }

    public static long packFrameIndex(long extractionSequence, String dimensionId) {
        CapturedEnvironment captured = latest;
        boolean sameDimension = captured.dimensionId().equals(dimensionId);
        int phase = sameDimension ? captured.dayPhase11() : 0;
        int moonPhase = sameDimension ? captured.moonPhase() : 0;
        int gpuWord = (dimensionCode(dimensionId) << 30)
                | ((phase & DAY_PHASE_MASK) << DAY_PHASE_SHIFT)
                | ((moonPhase & MOON_PHASE_MASK) << MOON_PHASE_SHIFT)
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

    static int dayPhase11(long dayTime) {
        long timeOfDay = Math.floorMod(dayTime, 24_000L);
        return (int) ((timeOfDay * 2_048L) / 24_000L) & DAY_PHASE_MASK;
    }

    static int gpuWord(long packedFrameIndex) {
        return (int) packedFrameIndex;
    }

    static int gpuDimensionCode(long packedFrameIndex) {
        return gpuWord(packedFrameIndex) >>> 30;
    }

    static int gpuDayPhase11(long packedFrameIndex) {
        return (gpuWord(packedFrameIndex) >>> DAY_PHASE_SHIFT) & DAY_PHASE_MASK;
    }

    static int gpuMoonPhase(long packedFrameIndex) {
        return (gpuWord(packedFrameIndex) >>> MOON_PHASE_SHIFT) & MOON_PHASE_MASK;
    }

    static int gpuFrameSeed(long packedFrameIndex) {
        return gpuWord(packedFrameIndex) & FRAME_SEED_MASK;
    }

    private record CapturedEnvironment(String dimensionId, int dayPhase11, int moonPhase) {
    }
}
