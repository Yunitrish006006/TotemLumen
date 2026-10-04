#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D DepthSampler;
uniform sampler2D NormalSampler;
uniform sampler2D MaterialIdAtlas;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

vec4 surfaceIdentity(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(96)))) return vec4(0);
    ivec3 section = cell / 16;
    int slot = (section.y * 6 + section.z) * 6 + section.x;
    ivec3 local = cell % 16;
    return texelFetch(MaterialIdAtlas, ivec2(
            (slot % 16) * 16 + local.x,
            (slot / 16) * 256 + local.y * 16 + local.z), 0);
}

void main() {
    fragColor = vec4(0);
    vec2 pixel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec2 uv = (floor(texCoord / pixel) + 0.5) * pixel;
    float depth = texture(DepthSampler, uv).r;
    if (depth <= 0.000001) return;

    vec4 packedNormal = texture(NormalSampler, uv);
    if (packedNormal.a <= 0.0) return;
    vec3 normal = normalize(packedNormal.rgb * 2.0 - 1.0);
    vec3 p = positionAt(uv, depth);

    // Surface normals face out of the primary surface. Step into the owning voxel rather than
    // selecting the air cell immediately in front of the rasterized surface.
    ivec3 cell = ivec3(floor(p + ModelOffset - normal * 0.08));
    fragColor = surfaceIdentity(cell);
}
