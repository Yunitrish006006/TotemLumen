package dev.totem.lumen.gameplay.light;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/** Regenerate the portable vanilla palette from the target Minecraft client JAR. */
public final class VanillaEmitterPaletteGenerator {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var table = new TreeMap<String, String>();
        for (var block : BuiltInRegistries.BLOCK) {
            var states = block.getStateDefinition().getPossibleStates();
            if (states.stream().noneMatch(state -> state.getLightEmission() > 0)) continue;
            var id = BuiltInRegistries.BLOCK.getKey(block);
            int rgb = BlockTextureColor.sampleOriginal(id.toString(), states);
            if (rgb != 0) table.put(id.getPath(), "%06x".formatted(rgb));
            else System.out.println("No visible model pixels for " + id);
        }
        if (!table.containsKey("copper_bulb")) throw new IllegalStateException("Missing vanilla client textures");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("# Generated from original Minecraft block models by generateVanillaEmitterPalette.\n");
            for (var entry : table.entrySet()) writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
        }
    }
}
