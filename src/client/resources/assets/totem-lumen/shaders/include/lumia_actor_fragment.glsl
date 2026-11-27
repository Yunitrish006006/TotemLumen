#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <totem-lumen:lumia_scene.glsl>

layout(location = 0) in vec3 relativePos;
layout(location = 1) in vec4 surfaceColor;
layout(location = 2) in vec2 surfaceUv;
layout(location = 3) in vec2 lightUv;
#if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
layout(location = 4) in vec3 surfaceNormal;
#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlay;
#endif
#endif
#ifdef GLINT
layout(location = 6) in vec2 glintUv;
uniform sampler2D GlintSampler;
#endif
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif
#ifndef LUMIA_LEASH
uniform sampler2D Sampler0;
#endif
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef LUMIA_LEASH
    vec4 base = vec4(1.0);
#else
    vec4 base = texture(Sampler0, surfaceUv);
#ifdef IS_GRAYSCALE
    base = base.rrrr;
#endif
#endif
#ifdef OIT_ADDITIVE
    base.a = min(0.99, base.a);
#endif
#ifdef ALPHA_CUTOUT
    if (base.a < ALPHA_CUTOUT) discard;
#endif
    vec4 tint = surfaceColor;
#ifdef DISSOLVE
    if (tint.a < texture(DissolveMaskSampler, surfaceUv).a) discard;
    tint.a = 1.0;
#endif
    base *= tint * ColorModulator;
#if defined(LUMIA_PARTICLE) || defined(LUMIA_TEXT)
    if (base.a < 0.1) discard;
#endif

#if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
    vec3 n = surfaceNormal;
#ifndef NO_OVERLAY
    base.rgb = mix(overlay.rgb, base.rgb, overlay.a);
#endif
#else
    vec3 n = cross(dFdx(relativePos), dFdy(relativePos));
#endif
    float n2 = dot(n,n);
    n = n2 > 1e-12 ? n * inversesqrt(n2) : vec3(0.0, 1.0, 0.0);
    if (dot(n, relativePos) > 0.0) n = -n;
    vec3 radiance = lumiaShade(base.rgb, relativePos, n, lightUv, 0.035);
#if defined(LUMIA_ITEM) || defined(LUMIA_TEXT)
    // Luminous item models and glow-ink glyphs carry an extra packed-light flag.
    if (lightUv.x > 1.5) radiance = max(radiance, base.rgb);
#endif
    base.rgb = radiance;
#ifdef GLINT
    vec4 glint = GlintAlpha * texture(GlintSampler, glintUv);
    base.rgb += glint.rgb * glint.rgb;
    base.a = max(base.a, GlintAlpha);
#endif
#ifdef OIT_ACCUMULATE
    base = sampleColorForAccumulation(base);
    vec4 fog = vec4(FogColor.rgb * base.a, FogColor.a);
#else
    vec4 fog = FogColor;
#endif
    fragColor = apply_fog(base, fog_spherical_distance(relativePos), fog_cylindrical_distance(relativePos),
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fog);
}
