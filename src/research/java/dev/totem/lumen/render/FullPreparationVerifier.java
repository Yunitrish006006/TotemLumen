package dev.totem.lumen.render;

import dev.totem.lumen.render.ProfilePreparation.Status;
import dev.totem.lumen.vulkan.P12FullBasePipeline.PrewarmStage;

/** A ready bootstrap can still lack either production compute pass or its cached compositor. */
public final class FullPreparationVerifier {
    private FullPreparationVerifier() {}

    public static void verify() {
        require(PrewarmStage.IDLE, Status.IDLE, Status.READY, Status.PREPARING);
        require(PrewarmStage.SPIRV_AND_DRIVER, Status.READY, Status.READY, Status.PREPARING);
        require(PrewarmStage.READY, Status.IDLE, Status.READY, Status.PREPARING);
        require(PrewarmStage.READY, Status.PREPARING, Status.READY, Status.PREPARING);
        require(PrewarmStage.READY, Status.READY, Status.IDLE, Status.PREPARING);
        require(PrewarmStage.FAILED, Status.IDLE, Status.READY, Status.FAILED);
        require(PrewarmStage.READY, Status.FAILED, Status.READY, Status.FAILED);
        require(PrewarmStage.READY, Status.READY, Status.FAILED, Status.FAILED);
        require(PrewarmStage.READY, Status.READY, Status.READY, Status.READY);
    }

    private static void require(PrewarmStage base, Status reflection, Status compositor, Status expected) {
        Status actual = RendererBootstrap.fullPreparationStatus(base, reflection, compositor);
        if (actual != expected) throw new AssertionError("Unexpected preparation status " + actual
                + " for " + base + ", " + reflection + ", " + compositor);
    }
}
