package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentFrameStateTest {
    @Test
    void mapsVanillaDimensionsToStableCodes() {
        assertEquals(EnvironmentFrameState.DIMENSION_OVERWORLD,
                EnvironmentFrameState.dimensionCode("minecraft:overworld"));
        assertEquals(EnvironmentFrameState.DIMENSION_NETHER,
                EnvironmentFrameState.dimensionCode("minecraft:the_nether"));
        assertEquals(EnvironmentFrameState.DIMENSION_END,
                EnvironmentFrameState.dimensionCode("minecraft:the_end"));
        assertEquals(EnvironmentFrameState.DIMENSION_OTHER,
                EnvironmentFrameState.dimensionCode("example:custom"));
    }

    @Test
    void quantizesTheMinecraftDayCycleIntoFourteenBits() {
        assertEquals(0, EnvironmentFrameState.dayPhase14(0));
        assertEquals(4096, EnvironmentFrameState.dayPhase14(6000));
        assertEquals(8192, EnvironmentFrameState.dayPhase14(12000));
        assertEquals(12288, EnvironmentFrameState.dayPhase14(18000));
        assertEquals(0, EnvironmentFrameState.dayPhase14(24000));
        assertEquals(12288, EnvironmentFrameState.dayPhase14(-6000));
    }

    @Test
    void packsDimensionDayPhaseAndFrameSeedWithoutLosingCpuSequence() {
        long sequence = 0x1234_5678L;
        EnvironmentFrameState.capture("minecraft:overworld", 6000L);

        long packed = EnvironmentFrameState.packFrameIndex(sequence, "minecraft:overworld");

        assertEquals(sequence, packed >>> 32);
        assertEquals(EnvironmentFrameState.DIMENSION_OVERWORLD,
                EnvironmentFrameState.gpuDimensionCode(packed));
        assertEquals(4096, EnvironmentFrameState.gpuDayPhase14(packed));
        assertEquals((int) sequence & 0xFFFF, EnvironmentFrameState.gpuFrameSeed(packed));
    }

    @Test
    void doesNotReuseDayPhaseAcrossDimensionMismatch() {
        long sequence = 17L;
        EnvironmentFrameState.capture("minecraft:overworld", 6000L);

        long packed = EnvironmentFrameState.packFrameIndex(sequence, "minecraft:the_nether");

        assertEquals(EnvironmentFrameState.DIMENSION_NETHER,
                EnvironmentFrameState.gpuDimensionCode(packed));
        assertEquals(0, EnvironmentFrameState.gpuDayPhase14(packed));
        assertEquals(17, EnvironmentFrameState.gpuFrameSeed(packed));
    }
}
