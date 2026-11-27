#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
#ifdef LUMIA_PARTICLE
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
#else
layout(location = 1) in vec4 Color;
#ifndef LUMIA_LEASH
layout(location = 2) in vec2 UV0;
#endif
#endif

#if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef LUMIA_ITEM
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;
#else
layout(location = 5) in vec3 Normal;
#endif
#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
layout(location = 5) out vec4 overlay;
#endif
#elif defined(LUMIA_LEASH)
layout(location = 2) in ivec2 UV2;
#else
layout(location = 3) in ivec2 UV2;
#endif

layout(location = 0) out vec3 relativePos;
layout(location = 1) out vec4 surfaceColor;
layout(location = 2) out vec2 surfaceUv;
layout(location = 3) out vec2 lightUv;
#if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
layout(location = 4) out vec3 surfaceNormal;
#endif
#ifdef GLINT
layout(location = 6) out vec2 glintUv;
#endif

void main() {
    // Native world meshes contain camera-relative world axes. The hand submission
    // already applies the inverse camera rotation before baking its vertices.
    relativePos = Position;
#ifdef LUMIA_BLOCK
    relativePos += ModelOffset;
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(relativePos, 1.0);
    surfaceColor = Color;
#ifdef LUMIA_LEASH
    surfaceUv = vec2(0.0);
#else
    surfaceUv = UV0;
#endif
#ifdef APPLY_TEXTURE_MATRIX
    surfaceUv = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
    lightUv = vec2(UV2) / 240.0;
#if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
    surfaceNormal = Normal;
#ifndef NO_OVERLAY
    overlay = texelFetch(Sampler1, UV1, 0);
#endif
#endif
#ifdef GLINT
#ifdef GLINT_SPECIAL
    glintUv = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
#else
    glintUv = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#endif
}
