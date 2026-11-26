package dev.totem.lumen.gameplay.light;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

final class BlockTextureColorTest {
    @Test
    void amethystUsesVisiblePixelsAndPreservesEmissionStrength() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var states = Blocks.AMETHYST_CLUSTER.getStateDefinition().getPossibleStates();
        try (var stream = getClass().getResourceAsStream("/assets/minecraft/textures/block/amethyst_cluster.png")) {
            assertNotNull(stream);
            var image = ImageIO.read(stream);
            long red = 0, green = 0, blue = 0, coverage = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    int alpha = pixel >>> 24;
                    red += (pixel >> 16 & 255) * alpha;
                    green += (pixel >> 8 & 255) * alpha;
                    blue += (pixel & 255) * alpha;
                    coverage += alpha;
                }
            }
            int r = Math.round((float) red / coverage);
            int g = Math.round((float) green / coverage);
            int b = Math.round((float) blue / coverage);
            assertEquals(r << 16 | g << 8 | b, BlockTextureColor.sampleOriginal("minecraft:amethyst_cluster", states));
            assertEquals(new EmissionColor((float) r / b, (float) g / b, 1),
                    DefaultEmissionColors.forBlock("minecraft:amethyst_cluster", 5));
            var state = Blocks.AMETHYST_CLUSTER.defaultBlockState();
            assertEquals(state.getLightEmission(), PackedRgbLight.alpha(GameplayLightSource.packedFor(state, null)));
        }
    }
}
