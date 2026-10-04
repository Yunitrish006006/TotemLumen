#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
#endif
layout(location = 0) out vec4 materialColor;
layout(location = 1) out vec2 materialUv;
void main() {
    vec3 relative = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
    // Native Color preserves biome tint AND baked AO/directional shade. Not pure albedo.
    materialColor = Color;
    materialUv = UV0;
}
