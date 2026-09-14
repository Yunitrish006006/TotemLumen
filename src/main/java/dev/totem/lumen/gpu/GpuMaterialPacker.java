package dev.totem.lumen.gpu;

import dev.totem.lumen.material.MaterialDefinition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Packs material metadata without Minecraft object references or source identifiers. */
public final class GpuMaterialPacker {
    private GpuMaterialPacker() {
    }

    public static byte[] pack(List<MaterialDefinition> materials) {
        ByteBuffer buffer = ByteBuffer
                .allocate(Math.multiplyExact(materials.size(), GpuSceneAbi.MATERIAL_STRIDE_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);

        for (MaterialDefinition material : materials) {
            int base = buffer.position();
            buffer.putInt(material.flags());
            buffer.putInt(material.emissionLevel());
            buffer.putFloat(material.roughness());
            buffer.putFloat(material.metallic());
            buffer.putFloat(material.opacity());
            buffer.putFloat(material.indexOfRefraction());
            buffer.putInt(0);
            buffer.putInt(0);

            if (buffer.position() - base != GpuSceneAbi.MATERIAL_STRIDE_BYTES) {
                throw new IllegalStateException("Material ABI stride mismatch");
            }
        }
        return buffer.array();
    }
}
