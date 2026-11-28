#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

#include <totem-lumen:lumia_scene.glsl>

#ifdef LUMIA_TRANSLUCENT
#include <totem-lumen:lumia_glass.glsl>
layout(location = 7) flat in int glassMaterial;
#endif
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

vec4 calculateFinalColor(vec4 color, bool applyAtmosphere) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    if (!applyAtmosphere) return color;
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    vec3 area = cross(dFdx(surfacePosition), dFdy(surfacePosition));
    vec3 normal = area * inversesqrt(max(dot(area, area), 1e-20));
    if (dot(normal, surfacePosition) > 0.0) normal = -normal;
    bool glassFace = false;
    #ifdef LUMIA_TRANSLUCENT
    glassFace = (glassMaterial & 1) != 0;
    vec3 incident = surfacePosition * inversesqrt(max(dot(surfacePosition, surfacePosition), 1e-12));
    #ifndef OIT_ALPHA_ONLY
    // Use framebuffer derivatives to account for the backend viewport's Y convention.
    vec2 projected = lumiaGlassProjection(surfacePosition).xy;
    vec2 orientation = sign(vec2(dFdx(projected.x), dFdy(projected.y)));
    #endif
    if (glassFace) {
        LumiaGlass glass = lumiaGlassMaterial(glassMaterial, color.a, incident, normal);
        color.a = glass.alpha;
        #ifndef OIT_ALPHA_ONLY
        vec3 marking = vec3(0.0);
        if (glass.markings > 0.0) marking = lumiaShade(color.rgb, surfacePosition, normal, surfaceLight, 0.0);
        float fog = FogColor.a * total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
                FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
        color.rgb = lumiaGlassColor(glass, marking, surfacePosition, normal, incident, orientation, fog);
        #endif
    }
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) discard;
    #endif
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    #ifdef LUMIA_TRANSLUCENT
    if (!glassFace) color.rgb = lumiaShade(color.rgb, surfacePosition, normal, surfaceLight, 0.12);
    #else
    color.rgb = lumiaShade(color.rgb, surfacePosition, normal, surfaceLight, 0.025);
    #endif
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    fragColor = calculateFinalColor(color, !glassFace);
    #endif
}
