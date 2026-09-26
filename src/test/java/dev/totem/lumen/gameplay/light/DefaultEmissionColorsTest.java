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

    @Test
    void litRedstoneOresUseRedLightButUnlitOresDoNotEmit() {
        EmissionColor red = DefaultEmissionColors.forBlock("minecraft:redstone_torch", 7);
        assertEquals(red, DefaultEmissionColors.forBlock("minecraft:redstone_ore", 9));
        assertEquals(red, DefaultEmissionColors.forBlock("minecraft:deepslate_redstone_ore", 9));
        assertEquals(EmissionColor.BLACK,
                DefaultEmissionColors.forBlock("minecraft:redstone_ore", 0));
        char packed = PackedRgbLight.fromNormalized(red, 9);
        assertTrue(PackedRgbLight.red(packed) > PackedRgbLight.green(packed));
        assertTrue(PackedRgbLight.red(packed) > PackedRgbLight.blue(packed));
    }

    @Test
    void soulLanternIsSlightlyBluerThanOtherSoulLights() {
        EmissionColor lantern = DefaultEmissionColors.forBlock("minecraft:soul_lantern", 10);
        EmissionColor torch = DefaultEmissionColors.forBlock("minecraft:soul_torch", 10);
        assertEquals(1.0f, lantern.blue());
        assertTrue(lantern.red() < torch.red());
        assertTrue(lantern.green() < torch.green());
        assertEquals(4, PackedRgbLight.hueRed(PackedRgbLight.fromNormalized(lantern, 10)));
        assertEquals(11, PackedRgbLight.hueGreen(PackedRgbLight.fromNormalized(lantern, 10)));
        assertEquals(15, PackedRgbLight.hueBlue(PackedRgbLight.fromNormalized(lantern, 10)));
    }
}
