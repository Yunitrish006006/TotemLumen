package dev.totem.lumen.vulkan;

import dev.totem.lumen.gameplay.light.RgbLightGpuRecordLayout;

/** Shared source contract for the future vanilla block RGBA-light RenderPipeline. */
public final class VanillaRgbShaderContract {
    public static final String SECTION_BUFFER_NAME = "TotemRgbLightSections";
    public static final String SECTION_COUNT_NAME = "TotemRgbLightSectionCount";

    private VanillaRgbShaderContract() {
    }

    /**
     * GLSL helper body for a texel-buffer-backed section table. The caller supplies the actual
     * binding declaration because Minecraft's RenderPipeline owns the bind-group layout.
     */
    public static String lookupSource() {
        return """
                const uint TOTEM_RGB_SECTION_SIZE = 16u * 16u * 16u;
                const uint TOTEM_RGB_SECTION_HEADER_WORDS = 4u;

                ivec3 totemRgbSectionCoord(ivec3 worldBlock) {
                    return ivec3(
                        int(floor(float(worldBlock.x) / 16.0)),
                        int(floor(float(worldBlock.y) / 16.0)),
                        int(floor(float(worldBlock.z) / 16.0))
                    );
                }

                uvec3 totemRgbLocalCoord(ivec3 worldBlock) {
                    return uvec3(
                        uint(worldBlock.x & 15),
                        uint(worldBlock.y & 15),
                        uint(worldBlock.z & 15)
                    );
                }

                uint totemRgbVoxelIndex(uvec3 local) {
                    return (local.y << 8u) | (local.z << 4u) | local.x;
                }

                vec4 totemRgbaUnpack(uint packed) {
                    return vec4(
                        float(packed & 15u),
                        float((packed >> 4u) & 15u),
                        float((packed >> 8u) & 15u),
                        float((packed >> 12u) & 15u)
                    ) / 15.0;
                }

                vec3 totemRgbUnpack(uint packed) {
                    vec4 light = totemRgbaUnpack(packed);
                    return light.rgb * light.a;
                }
                """;
    }

    public static int sectionBytes() {
        return RgbLightGpuRecordLayout.SECTION_BYTES;
    }
}
