package dev.totem.lumen.scene;

/**
 * Immutable camera/frame data captured during Minecraft's extraction phase.
 *
 * <p>The renderer can safely retain this record without retaining a Minecraft Camera, Level, Vec3,
 * or Quaternion object.</p>
 */
public record FrameSnapshot(
        long frameIndex,
        String dimensionId,
        double cameraX,
        double cameraY,
        double cameraZ,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        float fovDegrees,
        boolean detachedCamera
) {
    public FrameSnapshot {
        frameIndex = EnvironmentFrameState.packFrameIndex(frameIndex, dimensionId);
    }
}
