#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D SceneColorSampler;
uniform sampler2D PlacedRgbSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 placedCell(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(32)))) {
        return vec3(0.0);
    }
    int slot = int(TextureMat[2].w);
    ivec2 texel = ivec2((slot & 1) * 256 + (cell.z & 7) * 32 + cell.x,
            (slot >> 1) * 128 + cell.y * 4 + (cell.z >> 3));
    return texelFetch(PlacedRgbSampler, texel, 0).rgb;
}

vec3 placedLightAt(vec3 surfacePosition) {
    // The placed field is stored per voxel, while terrain uses smooth vertex
    // sampling. Nearest-voxel subtraction left square moving boundaries when
    // the hand crossed a block edge. Interpolate the same field continuously.
    vec3 voxelPosition = surfacePosition - TextureMat[2].xyz - vec3(0.5);
    ivec3 base = ivec3(floor(voxelPosition));
    vec3 fraction = fract(voxelPosition);
    vec3 near00 = mix(placedCell(base), placedCell(base + ivec3(1, 0, 0)), fraction.x);
    vec3 near01 = mix(placedCell(base + ivec3(0, 0, 1)),
            placedCell(base + ivec3(1, 0, 1)), fraction.x);
    vec3 far00 = mix(placedCell(base + ivec3(0, 1, 0)),
            placedCell(base + ivec3(1, 1, 0)), fraction.x);
    vec3 far01 = mix(placedCell(base + ivec3(0, 1, 1)),
            placedCell(base + ivec3(1, 1, 1)), fraction.x);
    return mix(mix(near00, near01, fraction.z),
            mix(far00, far01, fraction.z), fraction.y);
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth <= 0.000001) discard;

    float clipZ = TextureMat[0].y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 position = ModelViewMat * vec4(texCoord * 2.0 - 1.0, clipZ, 1.0);
    if (abs(position.w) < 0.000001) discard;
    vec3 worldPosition = position.xyz / position.w;
    float radialDistance = length(ModelOffset - worldPosition) * max(TextureMat[0].w, 1.0);
    float radius = TextureMat[0].x;
    if (radialDistance >= radius || radialDistance < 0.001) discard;

    float falloff = max(1.0 - radialDistance / radius, 0.0);
    vec3 scene = texture(SceneColorSampler, texCoord).rgb;
    // The terrain already contains placed RGB light. Sample its client-owned field
    // just outside the visible surface, toward this moving light's air cell.
    vec3 placed = placedLightAt(worldPosition + normalize(ModelOffset - worldPosition) * 0.5);
    vec3 baseline = max(TextureMat[1].rgb, vec3(0.04));
    vec3 reflectance = clamp(scene / max(baseline + placed, vec3(0.04)),
            vec3(0.0), vec3(1.0));
    vec3 tint = mix(vec3(1.0), ColorModulator.rgb, 0.55);
    // Keep the 0.90 handheld setting, but concentrate more of its illumination
    // near the source instead of lighting the whole radius nearly uniformly.
    float shapedFalloff = 1.2 * falloff * falloff * (1.5 - 0.5 * falloff);
    vec3 held = tint * (ColorModulator.a * shapedFalloff);
    // Component-wise max matches the placed RGB field's source-combination rule.
    // Only the illumination missing from that field may be added to the scene.
    vec3 missing = max(held - placed, vec3(0.0));
    vec3 contribution = reflectance * missing * (vec3(1.0) - scene);
    fragColor = vec4(contribution, 0.0);
}
