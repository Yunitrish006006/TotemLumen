#version 330
#extension GL_ARB_separate_shader_objects : require
#include <totem-lumen:raster_material_decode.glsl>

uniform sampler2D VisibleSurfaceIdentity;
uniform sampler2D MaterialLut;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 identity = texture(VisibleSurfaceIdentity, texCoord);
    uint materialId = rasterMaterialId(identity);
    if (materialId == 0u) {
        fragColor = vec4(1.0, 0.0, 0.0, 0.0);
        return;
    }

    float roughness = clamp(rasterMaterialFloat(MaterialLut, materialId, 2u), 0.0, 1.0);
    float metallic = clamp(rasterMaterialFloat(MaterialLut, materialId, 3u), 0.0, 1.0);
    float opacity = clamp(rasterMaterialFloat(MaterialLut, materialId, 4u), 0.0, 1.0);
    float emission = clamp(float(rasterMaterialWord(MaterialLut, materialId, 1u)) / 15.0, 0.0, 1.0);

    // Base MaterialDefinition values only. P18 texture/surface overrides and unlit albedo
    // remain unresolved and therefore cannot enable independent DIRECT_LIGHT.
    fragColor = vec4(roughness, metallic, opacity, emission);
}
