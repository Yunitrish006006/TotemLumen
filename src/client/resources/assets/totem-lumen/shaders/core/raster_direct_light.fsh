#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D NormalSampler;
uniform sampler2D UnlitAlbedoSampler;
uniform sampler2D VoxelSampler;
uniform sampler2D LightSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

vec4 voxel(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(96)))) return vec4(0);
    ivec3 section = cell / 16;
    int slot = (section.y * 6 + section.z) * 6 + section.x;
    ivec3 local = cell % 16;
    return texelFetch(VoxelSampler, ivec2(
            (slot % 16) * 16 + local.x,
            (slot / 16) * 256 + local.y * 16 + local.z), 0);
}

// Conservative direct-light visibility. Unknown cells block instead of leaking light.
float visibility(vec3 origin, vec3 target) {
    vec3 vector = target - origin;
    float lengthToLight = length(vector);
    if (lengthToLight < 0.001) return 1.0;

    vec3 direction = vector / lengthToLight;
    ivec3 cell = ivec3(floor(origin));
    ivec3 destination = ivec3(floor(target));
    ivec3 stepDirection = ivec3(sign(direction));
    vec3 delta = 1.0 / max(abs(direction), vec3(0.00001));
    vec3 next = abs(vec3(cell) + step(vec3(0), direction) - origin) * delta;

    for (int i = 0; i < 64; i++) {
        vec4 v = voxel(cell);
        if (v.a < 0.25) return 0.0;
        if (all(equal(cell, destination))) return 1.0;
        if (v.a > 0.75) return 0.0;

        float distance = min(next.x, min(next.y, next.z));
        if (distance > lengthToLight) return 0.0;
        bvec3 axis = lessThanEqual(next, vec3(distance + 0.000001));
        cell += ivec3(axis) * stepDirection;
        next += vec3(axis) * delta;
    }
    return 0.0;
}

vec3 directRgb(vec3 surface, vec3 normal) {
    vec3 positions[4];
    vec3 colours[4];
    float weights[4];
    for (int i = 0; i < 4; i++) {
        positions[i] = vec3(0);
        colours[i] = vec3(0);
        weights[i] = 0.0;
    }

    for (int i = 0; i < 32; i++) {
        vec4 packed = texelFetch(LightSampler, ivec2(i * 2, 0), 0);
        if (packed.a < 0.5) break;

        vec3 position = floor(packed.rgb * 255.0 + 0.5) + 0.5;
        vec3 colour = texelFetch(LightSampler, ivec2(i * 2 + 1, 0), 0).rgb;
        vec3 toLight = position - surface;
        float distance = length(toLight);
        float strength = max(colour.r, max(colour.g, colour.b));
        float range = strength * 12.5;
        if (distance <= 0.001 || distance >= range) continue;

        float falloff = 1.0 - distance / range;
        float weight = falloff * falloff * (3.0 - 2.0 * falloff)
                * max(dot(normal, toLight / distance), 0.0);

        for (int j = 0; j < 4; j++) {
            if (weight * strength > weights[j] * max(colours[j].r, max(colours[j].g, colours[j].b))) {
                float oldWeight = weights[j];
                weights[j] = weight;
                weight = oldWeight;

                vec3 oldPosition = positions[j];
                positions[j] = position;
                position = oldPosition;

                vec3 oldColour = colours[j];
                colours[j] = colour;
                colour = oldColour;
                strength = max(colour.r, max(colour.g, colour.b));
            }
        }
    }

    vec3 result = vec3(0);
    for (int i = 0; i < 4; i++) {
        if (weights[i] <= 0.0) continue;
        result += colours[i] * weights[i]
                * visibility(surface + normal * 0.08, positions[i]);
    }
    return result;
}

void main() {
    fragColor = vec4(0.0);

    // MATERIAL_RESOLVE alpha is explicit coverage. Unsupported surfaces remain native fallback.
    float coverage = texture(UnlitAlbedoSampler, texCoord).a;
    if (coverage <= 0.0) return;

    float depth = texture(DepthSampler, texCoord).r;
    vec4 packedNormal = texture(NormalSampler, texCoord);
    if (depth <= 0.000001 || packedNormal.a <= 0.0) return;

    vec3 normal = normalize(packedNormal.rgb * 2.0 - 1.0);
    if (any(isnan(normal)) || any(isinf(normal))) return;

    vec3 p = positionAt(texCoord, depth);
    if (any(isnan(p)) || any(isinf(p))) return;

    vec3 scenePosition = p + ModelOffset;
    vec3 direct = directRgb(scenePosition, normal) * TextureMat[0].w;
    fragColor = vec4(direct, coverage);
}
