package dev.totem.lumen.scene;

import java.util.function.Consumer;

/**
 * Shared block-to-section coordinate helpers. Minecraft uses floor division for negative world
 * coordinates, so every caller must use these helpers instead of truncating integer division.
 */
public final class SectionCoordinates {
    public static final int SECTION_SIZE = 16;

    private SectionCoordinates() {
    }

    public static int blockToSection(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, SECTION_SIZE);
    }

    /**
     * Emits every section touched by Minecraft's 3x3x3 renderer-dirty halo around one block.
     * The result contains between one and eight unique section keys.
     */
    public static void forDirtyHalo(
            String dimensionId,
            int blockX,
            int blockY,
            int blockZ,
            Consumer<SectionKey> consumer
    ) {
        int minSectionX = blockToSection(blockX - 1);
        int maxSectionX = blockToSection(blockX + 1);
        int minSectionY = blockToSection(blockY - 1);
        int maxSectionY = blockToSection(blockY + 1);
        int minSectionZ = blockToSection(blockZ - 1);
        int maxSectionZ = blockToSection(blockZ + 1);

        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    consumer.accept(new SectionKey(dimensionId, sectionX, sectionY, sectionZ));
                }
            }
        }
    }
}
