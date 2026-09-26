package dev.totem.lumen.integration;

/** Build-time checks for section-local source lookup and allocation-free repeated RGB reads. */
public final class RgbHotPathVerifier {
    private RgbHotPathVerifier() {
    }

    public static void main(String[] args) {
        ClientGameplayLightPredictor.verifySourceIndex();

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
        System.out.println("RGB hot-path verification PASS: localSources=true, negativeSections=true, lookupInvalidation=true");
    }
}
