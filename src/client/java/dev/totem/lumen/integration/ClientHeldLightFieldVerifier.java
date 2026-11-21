package dev.totem.lumen.integration;

import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.gameplay.light.RgbLightAttenuation;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Exercise the real terrain sampler with a deterministic enclosed corridor crossing x=0. */
final class ClientHeldLightFieldVerifier {
    static void verify() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var wall = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        String dimension = "lumen:held_light_test";
        var previous = ClientHeldLightField.visible;
        ClientGameplayLightField.setActiveDimension(dimension);
        char red = PackedRgbLight.pack(14, 0, 0);
        char blue = PackedRgbLight.pack(0, 0, 11);
        var redSeed = new ClientHeldLightField.Seed(new BlockPos(-2, 64, 0), red);
        var blueSeed = new ClientHeldLightField.Seed(new BlockPos(-2, 64, 0), blue);
        ClientHeldLightField.Transfer corridor = (x, y, z, packed) -> {
            var state = y == 64 && z == 0 && x != 2 ? air : wall;
            return RgbLightAttenuation.attenuate(packed, state, 1.0f);
        };
        try {
            for (var seeds : List.of(List.of(redSeed), List.of(redSeed, redSeed),
                    List.of(redSeed, blueSeed), List.of(blueSeed, redSeed))) {
                var job = new ClientHeldLightField.Flood(seeds);
                while (!job.advance(corridor, 2, Long.MAX_VALUE)) { /* exercise sliced propagation */ }
                ClientHeldLightField.visible = new ClientHeldLightField.Snapshot(dimension, job.sections);
                char placedRed = red;
                char placedBlue = seeds.contains(blueSeed) ? blue : 0;
                for (int x = -2; x <= 4; x++) {
                    int expected = PackedRgbLight.componentMax(placedRed, placedBlue);
                    int actual = ClientGameplayLightField.localSampler().packedAtCurrentDimension(x, 64, 0);
                    if (actual != expected) throw new AssertionError("Held/placed mismatch at x=" + x);
                    if (ClientGameplayLightField.serverPackedAt(dimension, x, 64, 0) != 0)
                        throw new AssertionError("Held light leaked into the authoritative field");
                    placedRed = corridor.enter(x + 1, 64, 0, placedRed);
                    placedBlue = corridor.enter(x + 1, 64, 0, placedBlue);
                }
            }
            var movingField = ClientHeldLightField.visible;
            int heldTint = VanillaRgbLighting.modelTint(-1.5, 64.5, 0.5, 0, 0);
            int blockedTint = VanillaRgbLighting.modelTint(2.5, 64.5, 0.5, 0, 0);
            if (net.minecraft.util.ARGB.red(heldTint) <= net.minecraft.util.ARGB.red(blockedTint)
                    || net.minecraft.util.ARGB.blue(heldTint) <= net.minecraft.util.ARGB.blue(blockedTint)
                    || net.minecraft.util.ARGB.green(heldTint) != net.minecraft.util.ARGB.green(blockedTint))
                throw new AssertionError("Entity RGB sample lost mixed color or wall occlusion");
            ClientHeldLightField.visible = previous;
            ClientGameplayLightField.setLocalPacked(dimension, -2, 64, 0, PackedRgbLight.componentMax(red, blue));
            ClientGameplayLightField.finishLocalBatch(true);
            if (VanillaRgbLighting.modelTint(-1.5, 64.5, 0.5, 0, 0) != heldTint)
                throw new AssertionError("Placed and held model tints differ at the same probe");
            ClientHeldLightField.visible = movingField;
            ClientGameplayLightField.setLocalPacked(dimension, -2, 64, 0, red);
            ClientGameplayLightField.finishLocalBatch(true);
            if (ClientGameplayLightField.localSampler().packedAtCurrentDimension(-2, 64, 0)
                    != PackedRgbLight.componentMax(red, blue))
                throw new AssertionError("Held and placed light must merge before terrain shading");
            ClientHeldLightField.visible = previous;
            if (ClientGameplayLightField.localSampler().packedAtCurrentDimension(-2, 64, 0) != red)
                throw new AssertionError("Unequip must reveal the remaining placed light");
            System.out.println("Held/placed terrain parity PASS: single, duplicate, mixed order, wall, boundary, removal, server isolation");
        } finally {
            ClientHeldLightField.visible = previous;
            ClientGameplayLightField.clear();
        }
    }

    private ClientHeldLightFieldVerifier() {}
}
