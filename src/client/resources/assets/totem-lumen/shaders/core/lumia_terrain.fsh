#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

#include <totem-lumen:lumia_scene.glsl>

uniform sampler2D Sampler0;
layout(location = 5) in vec3 surfacePosition;
layout(location = 6) in vec2 surfaceLight;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    vec3 area = cross(dFdx(surfacePosition), dFdy(surfacePosition));
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    vec3 normal = area * inversesqrt(max(dot(area, area), 1e-20));
    if (dot(normal, surfacePosition) > 0.0) normal = -normal;
    #ifdef LUMIA_TRANSLUCENT
    float reflectivity = 0.12;
    #else
    float reflectivity = 0.025;
    #endif
    color.rgb = lumiaShade(color.rgb, surfacePosition, normal, surfaceLight, reflectivity);
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    fragColor = calculateFinalColor(color);
    #endif
}
