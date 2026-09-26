#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    // Minecraft 26.3 uses reverse Z. Zero is the sky/clear depth, not a surface.
    if (depth <= 0.000001) discard;

    float clipZ = TextureMat[0].y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 position = ModelViewMat * vec4(texCoord * 2.0 - 1.0, clipZ, 1.0);
    if (abs(position.w) < 0.000001) discard;
    vec3 worldPosition = position.xyz / position.w;
    vec3 toLight = ModelOffset - worldPosition;
    float distanceToLight = length(toLight);
    // Match the placed RGB field's source-to-cell radial distance scale. The same
    // scale is uploaded by HeldLightPostRenderer for both vanilla-world profiles.
    float radialDistance = distanceToLight * max(TextureMat[0].w, 1.0);
    float radius = TextureMat[0].x;
    if (radialDistance >= radius || radialDistance < 0.001) discard;

    // A depth-derived normal changes at cutout grass, block edges and near-camera surfaces.
    // Use world-space radial light instead, so changing camera mode does not create dark gaps.
    float falloff = max(1.0 - radialDistance / radius, 0.0);
    // Minecraft Pure retains its independent, lightweight vanilla-compatible held light.
    float light = ColorModulator.a * falloff * falloff;
    fragColor = vec4(ColorModulator.rgb * light, 1.0);
}
