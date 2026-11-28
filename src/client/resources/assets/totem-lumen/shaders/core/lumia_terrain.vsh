#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef LUMIA_NATIVE_LIGHT
layout(location = 7) flat out int terrainMaterial;
#endif
#ifdef LUMIA_NATIVE_LIGHT
#include <minecraft:sample_lightmap.glsl>
uniform sampler2D Sampler2;
#else
layout(location = 5) out vec3 surfacePosition;
layout(location = 6) out vec2 surfaceLight;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifdef LUMIA_NATIVE_LIGHT
    vertexColor = Color * sample_lightmap(Sampler2, ivec2(UV2.x & 255, UV2.y));
    #else
    vertexColor = Color;
    surfacePosition = pos;
    terrainMaterial = UV2.x >> 8;
    surfaceLight = clamp(vec2(UV2.x & 255, UV2.y) / 240.0, 0.0, 1.0);
    #endif
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
}
