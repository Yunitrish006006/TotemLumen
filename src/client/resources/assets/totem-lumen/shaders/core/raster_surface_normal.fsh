#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D DepthSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

void main() {
    fragColor = vec4(0);
    vec2 pixel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec2 uv = (floor(texCoord / pixel) + 0.5) * pixel;
    float depth = texture(DepthSampler, uv).r;
    if (depth <= 0.000001) return;

    vec3 p = positionAt(uv, depth);
    vec3 left = p - positionAt(uv - vec2(pixel.x, 0),
            texture(DepthSampler, uv - vec2(pixel.x, 0)).r);
    vec3 right = positionAt(uv + vec2(pixel.x, 0),
            texture(DepthSampler, uv + vec2(pixel.x, 0)).r) - p;
    vec3 down = p - positionAt(uv - vec2(0, pixel.y),
            texture(DepthSampler, uv - vec2(0, pixel.y)).r);
    vec3 up = positionAt(uv + vec2(0, pixel.y),
            texture(DepthSampler, uv + vec2(0, pixel.y)).r) - p;
    vec3 dx = dot(left, left) < dot(right, right) ? left : right;
    vec3 dy = dot(down, down) < dot(up, up) ? down : up;
    vec3 n = cross(dx, dy);
    if (any(isnan(n)) || any(isinf(n)) || dot(n, n) < 0.0000000001) return;
    n = normalize(n);
    if (dot(n, p) > 0.0) n = -n;
    fragColor = vec4(n * 0.5 + 0.5, 1.0);
}
