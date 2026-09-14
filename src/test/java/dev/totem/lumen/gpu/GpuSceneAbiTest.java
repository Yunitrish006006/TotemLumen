package dev.totem.lumen.gpu;

import dev.totem.lumen.material.MaterialDefinition;
import dev.totem.lumen.material.MaterialFlags;
import dev.totem.lumen.scene.SectionVoxelData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuSceneAbiTest {
    @Test
    void sectionPackingPreservesSectionVoxelIndexing() {
        int[] ids = new int[SectionVoxelData.VOXEL_COUNT];
        int index = SectionVoxelData.index(3, 7, 11);
        ids[index] = 0x12345678;

        byte[] packed = GpuSectionPacker.pack(new SectionVoxelData(ids));
        ByteBuffer view = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(GpuSceneAbi.SECTION_VOXEL_BYTES, packed.length);
        assertEquals(0x12345678, view.getInt(index * Integer.BYTES));
    }

    @Test
    void materialPackingUsesStableThirtyTwoByteStride() {
        MaterialDefinition material = new MaterialDefinition(
                "test:emissive_glass",
                MaterialFlags.TRANSLUCENT | MaterialFlags.EMISSIVE,
                12,
                0.25f,
                0.5f,
                0.4f,
                1.5f
        );

        byte[] packed = GpuMaterialPacker.pack(List.of(MaterialDefinition.AIR, material));
        ByteBuffer view = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        int base = GpuSceneAbi.MATERIAL_STRIDE_BYTES;

        assertEquals(GpuSceneAbi.MATERIAL_STRIDE_BYTES * 2, packed.length);
        assertEquals(material.flags(), view.getInt(base + GpuSceneAbi.MATERIAL_FLAGS_OFFSET));
        assertEquals(12, view.getInt(base + GpuSceneAbi.MATERIAL_EMISSION_OFFSET));
        assertEquals(0.25f, view.getFloat(base + GpuSceneAbi.MATERIAL_ROUGHNESS_OFFSET));
        assertEquals(0.5f, view.getFloat(base + GpuSceneAbi.MATERIAL_METALLIC_OFFSET));
        assertEquals(0.4f, view.getFloat(base + GpuSceneAbi.MATERIAL_OPACITY_OFFSET));
        assertEquals(1.5f, view.getFloat(base + GpuSceneAbi.MATERIAL_IOR_OFFSET));
    }
}
