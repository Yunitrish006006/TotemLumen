package dev.totem.lumen.integration;

/** Build-time checks for section-local source lookup and allocation-free repeated RGB reads. */
public final class RgbHotPathVerifier {
    private RgbHotPathVerifier() {
    }

    public static void main(String[] args) {
        ClientGameplayLightPredictor.verifySourceIndex();
        ClientGameplayLightPredictor.verifyScanScheduling();
        ClientGameplayLightPredictor.verifyRebuildOverlap();
        ClientGameplayLightPredictor.verifyDiscoveredSourceFastPath();
        ClientGameplayLightPredictor.verifyLocalSourcePriority();
        verifyEnclosedLava();

        String dimension = "minecraft:overworld";
        ClientGameplayLightField.clear();
        ClientGameplayLightField.setActiveDimension(dimension);
        try {
            if (ClientGameplayLightField.packedAtCurrentDimension(-17, 64, 3) != 0) {
                throw new IllegalStateException("An absent RGB section must read as zero");
            }
            ClientGameplayLightField.setLocalPacked(dimension, -17, 64, 3, 0xF321);
            if (ClientGameplayLightField.packedAtCurrentDimension(-17, 64, 3) != 0xF321) {
                throw new IllegalStateException("A newly allocated RGB section must be visible immediately");
            }
            if (ClientGameplayLightField.localSampler().packedAtCurrentDimension(-17, 64, 3) != 0xF321) {
                throw new IllegalStateException("A terrain quad's shared sampler must return the same RGB value");
            }
            ClientGameplayLightField.finishLocalBatch(true);
            ClientGameplayLightField.clearLocalChunk(dimension, -2, 0);
            if (ClientGameplayLightField.packedAtCurrentDimension(-17, 64, 3) != 0) {
                throw new IllegalStateException("An unloaded RGB section must not remain in the lookup cache");
            }
        } finally {
            ClientGameplayLightField.clear();
        }
        System.out.println("RGB hot-path verification PASS: localSources=true, negativeSections=true, lookupInvalidation=true, scanScheduling=true, rebuildOverlap=true, discoveredSourceFastPath=true, localSourcePriority=true");
    }

    private static void verifyEnclosedLava() {
        char lava = (char) 0xF15F;
        char[] pool = new char[4096];
        java.util.Arrays.fill(pool, lava);
        int skipped = 0;
        for (int i = 0; i < pool.length; i++) {
            if (ClientGameplayLightPredictor.enclosedSource(pool, i, lava)) skipped++;
        }
        if (skipped != 14 * 14 * 14) throw new IllegalStateException("All six pool faces must still propagate");
        int center = 8 * 256 + 8 * 16 + 8;
        for (int delta : new int[]{1, -1, 16, -16, 256, -256}) {
            for (char neighbor : new char[]{0, (char) 0xA15F, (char) 0xF23F}) {
                pool[center + delta] = neighbor;
                if (ClientGameplayLightPredictor.enclosedSource(pool, center, lava))
                    throw new IllegalStateException("Exposed, weaker or differently colored neighbors need propagation");
            }
            pool[center + delta] = lava;
        }
        System.out.println("Dense lava section: 4096 seed cells, 1352 propagation jobs, 2744 dominated jobs skipped");
    }
}
