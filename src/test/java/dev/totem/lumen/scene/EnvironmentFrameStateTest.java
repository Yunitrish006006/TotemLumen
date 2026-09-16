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
    void quantizesTheMinecraftDayCycleIntoElevenBits() {
        assertEquals(0, EnvironmentFrameState.dayPhase11(0));
        assertEquals(512, EnvironmentFrameState.dayPhase11(6000));
        assertEquals(1024, EnvironmentFrameState.dayPhase11(12000));
        assertEquals(1536, EnvironmentFrameState.dayPhase11(18000));
        assertEquals(0, EnvironmentFrameState.dayPhase11(24000));
        assertEquals(1536, EnvironmentFrameState.dayPhase11(-6000));
    }

    @Test
    void packsDimensionDayPhaseMoonPhaseAndFullFrameSeedWithoutLosingCpuSequence() {
        long sequence = 0x1234_5678L;
        EnvironmentFrameState.capture("minecraft:overworld", 6000L, 6);

        long packed = EnvironmentFrameState.packFrameIndex(sequence, "minecraft:overworld");

        assertEquals(sequence, packed >>> 32);
        assertEquals(EnvironmentFrameState.DIMENSION_OVERWORLD,
                EnvironmentFrameState.gpuDimensionCode(packed));
        assertEquals(512, EnvironmentFrameState.gpuDayPhase11(packed));
        assertEquals(6, EnvironmentFrameState.gpuMoonPhase(packed));
        assertEquals((int) sequence & 0xFFFF, EnvironmentFrameState.gpuFrameSeed(packed));
    }

    @Test
    void doesNotReuseEnvironmentStateAcrossDimensionMismatch() {
        long sequence = 17L;
        EnvironmentFrameState.capture("minecraft:overworld", 6000L, 7);

        long packed = EnvironmentFrameState.packFrameIndex(sequence, "minecraft:the_nether");

        assertEquals(EnvironmentFrameState.DIMENSION_NETHER,
                EnvironmentFrameState.gpuDimensionCode(packed));
        assertEquals(0, EnvironmentFrameState.gpuDayPhase11(packed));
        assertEquals(0, EnvironmentFrameState.gpuMoonPhase(packed));
        assertEquals(17, EnvironmentFrameState.gpuFrameSeed(packed));
    }

    @Test
    void masksMoonPhaseToThreeBits() {
        EnvironmentFrameState.capture("minecraft:overworld", 0L, 13);

        long packed = EnvironmentFrameState.packFrameIndex(1L, "minecraft:overworld");

        assertEquals(5, EnvironmentFrameState.gpuMoonPhase(packed));
    }
}
