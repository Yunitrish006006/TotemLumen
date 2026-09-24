package dev.totem.lumen.gameplay.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEmissionColorsTest {
    @Test
    void ordinaryTorchesAreWarmerThanWhiteButLessRedThanBefore() {
        EmissionColor torch = DefaultEmissionColors.forBlock("minecraft:torch", 14);
        assertEquals(torch, DefaultEmissionColors.forBlock("minecraft:wall_torch", 14));
        assertEquals(1.0f, torch.red());
        assertTrue(torch.green() > 0.55f && torch.green() < 1.0f);
        assertTrue(torch.blue() > 0.22f && torch.blue() < torch.green());
    }

    @Test
    void specialtyTorchPalettesStaySeparate() {
        EmissionColor torch = DefaultEmissionColors.forBlock("minecraft:torch", 14);
        assertTrue(DefaultEmissionColors.forBlock("minecraft:soul_torch", 10).blue() > torch.blue());
        assertTrue(DefaultEmissionColors.forBlock("minecraft:redstone_torch", 7).green() < torch.green());
    }
}
