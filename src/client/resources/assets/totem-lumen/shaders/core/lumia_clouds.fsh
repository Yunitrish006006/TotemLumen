#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>
#include <totem-lumen:lumia_scene.glsl>

layout(location = 0) in vec3 relativePos;
layout(location = 1) flat in vec3 surfaceNormal;
layout(location = 2) flat in float opacity;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 n = dot(surfaceNormal, relativePos) > 0.0 ? -surfaceNormal : surfaceNormal;
    vec4 color = vec4(lumiaShade(vec3(.95), relativePos, n, vec2(0.0), 0.0), opacity);
#ifndef OIT_DEPTH_BOUNDS
    color.a *= 1.0 - linear_fog_value(fog_spherical_distance(relativePos), 0.0, FogCloudsEnd);
#endif
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif
    fragColor = color;
}
