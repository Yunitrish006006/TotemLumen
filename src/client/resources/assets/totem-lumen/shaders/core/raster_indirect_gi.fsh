#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D DepthSampler;
uniform sampler2D VoxelSampler;
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
    return texelFetch(VoxelSampler, ivec2((slot % 16) * 16 + local.x,
            (slot / 16) * 256 + local.y * 16 + local.z), 0);
}

// Secondary diffuse/occlusion traversal only. Primary visibility belongs to SURFACE_CAPTURE.
vec4 secondary(vec3 origin, vec3 direction) {
    ivec3 cell = ivec3(floor(origin));
    ivec3 stepDirection = ivec3(sign(direction));
    vec3 delta = 1.0 / max(abs(direction), vec3(0.00001));
    vec3 boundary = vec3(cell) + step(vec3(0), direction);
    vec3 next = abs(boundary - origin) * delta;
    float travelled = 0.0;
    for (int i = 0; i < 64; i++) {
        vec4 v = voxel(cell);
        if (v.a < 0.25) return vec4(0);
        if (v.a > 0.75 || max(max(v.r, v.g), v.b) > 0.01) {
            float attenuation = max(0.0, 1.0 - travelled / TextureMat[0].x);
            return vec4(v.rgb * attenuation, 1);
        }
        float distance = min(next.x, min(next.y, next.z));
        if (distance > TextureMat[0].x) break;
        travelled = distance;
        bvec3 axis = lessThanEqual(next, vec3(distance + 0.000001));
        cell += ivec3(axis) * stepDirection;
        next += vec3(axis) * delta;
    }
    return vec4(0);
}

void main() {
    fragColor = vec4(1, 1, 1, 0);
    vec2 pixel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec2 surfaceUv = (floor(texCoord / pixel) + 0.5) * pixel;
    float depth = texture(DepthSampler, surfaceUv).r;
    if (depth <= 0.000001) return;
    vec3 p = positionAt(surfaceUv, depth);
    float distance = length(p);
    if (isnan(distance) || isinf(distance) || distance >= TextureMat[0].x) return;

    vec3 left = p - positionAt(surfaceUv - vec2(pixel.x, 0),
            texture(DepthSampler, surfaceUv - vec2(pixel.x, 0)).r);
    vec3 right = positionAt(surfaceUv + vec2(pixel.x, 0),
            texture(DepthSampler, surfaceUv + vec2(pixel.x, 0)).r) - p;
    vec3 down = p - positionAt(surfaceUv - vec2(0, pixel.y),
            texture(DepthSampler, surfaceUv - vec2(0, pixel.y)).r);
    vec3 up = positionAt(surfaceUv + vec2(0, pixel.y),
            texture(DepthSampler, surfaceUv + vec2(0, pixel.y)).r) - p;
    vec3 dx = dot(left,left) < dot(right,right) ? left : right;
    vec3 dy = dot(down,down) < dot(up,up) ? down : up;
    vec3 crossNormal = cross(dx,dy);
    if (any(isnan(crossNormal)) || any(isinf(crossNormal))
            || dot(crossNormal,crossNormal) < 0.0000000001) return;
    vec3 normal = normalize(crossNormal);
    if (dot(normal, p) > 0) normal = -normal;

    vec3 tangent = normalize(cross(normal, abs(normal.y) < 0.9 ? vec3(0,1,0) : vec3(1,0,0)));
    vec3 bitangent = cross(normal, tangent);
    int samples = clamp(int(TextureMat[0].y), 1, 3);
    float occlusion = 0;
    vec3 bounce = vec3(0);
    vec3 origin = p + ModelOffset + normal * 0.08;
    for (int i = 0; i < 3; i++) {
        if (i >= samples) break;
        float angle = float(i) * 2.399963;
        vec3 direction = normalize(normal + 0.7 * (cos(angle) * tangent + sin(angle) * bitangent));
        vec4 hit = secondary(origin, direction);
        occlusion += hit.a;
        bounce += hit.rgb;
    }

    float weight = 1.0 - smoothstep(TextureMat[0].x * 0.75, TextureMat[0].x, distance);
    vec3 result = vec3(1.0 - weight * 0.2 * occlusion / float(samples))
            + weight * bounce / float(samples) * 0.6 * TextureMat[0].w;
    fragColor = vec4(result, distance);
}
