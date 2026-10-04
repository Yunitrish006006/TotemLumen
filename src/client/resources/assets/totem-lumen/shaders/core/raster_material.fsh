#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
uniform sampler2D Sampler0;
layout(location = 0) in vec4 materialColor;
layout(location = 1) in vec2 materialUv;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 base = (UseRgss == 1 ? sampleRGSS(Sampler0, materialUv, 1.0 / TextureSize)
                            : sampleNearest(Sampler0, materialUv, 1.0 / TextureSize)) * materialColor;
#ifdef ALPHA_CUTOUT
    if (base.a < ALPHA_CUTOUT) discard;
#endif
    // Alpha marks surface coverage. No fog, chunk fade, lightmap, or lighting correction.
    fragColor = vec4(base.rgb, 1.0);
}
