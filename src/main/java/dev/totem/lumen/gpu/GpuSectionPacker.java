package dev.totem.lumen.gpu;

import dev.totem.lumen.scene.SectionVoxelData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs one 16x16x16 section into the stable little-endian GPU voxel ABI. */
public final class GpuSectionPacker {
    private GpuSectionPacker() {
    }

    public static byte[] pack(SectionVoxelData voxels) {
        ByteBuffer buffer = ByteBuffer
                .allocate(GpuSceneAbi.SECTION_VOXEL_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        int[] materialIds = voxels.copyMaterialIds();
        for (int materialId : materialIds) {
            if (materialId < 0) {
                throw new IllegalArgumentException("Material IDs must be non-negative");
            }
            buffer.putInt(materialId);
        }
        return buffer.array();
    }
}
