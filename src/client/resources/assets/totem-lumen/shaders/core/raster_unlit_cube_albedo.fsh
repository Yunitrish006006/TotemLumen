#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <totem-lumen:raster_material_decode.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D NormalSampler;
uniform sampler2D VisibleSurfaceIdentity;
uniform sampler2D SurfaceSetLut;
uniform sampler2D PbrTextureLut;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Matches GpuPbrSurfaceSetScene ABI v2.
const uint SURFACE_ABI_VERSION = 2u;
const uint SURFACE_RECORD_BASE = 8u;
const uint SURFACE_RECORD_WORDS = 62u;
const uint SURFACE_FACE_WORDS = 10u;
const uint SURFACE_FACE_TINT_WORD = 9u;

// Matches GpuPbrTextureScene ABI v4.
const uint TEXTURE_ABI_VERSION = 4u;
const uint TEXTURE_DESCRIPTOR_BASE = 8u;
const uint TEXTURE_DESCRIPTOR_WORDS = 24u;
const uint TEXTURE_TEXEL_POOL_BASE = 163848u;
const uint TEXTURE_TEXEL_WORDS = 3u;
const uint TEXTURE_FLAG_ANIMATED = 16u;

uint rawWord(sampler2D source, uint word) {
    ivec2 size = textureSize(source, 0);
    uint width = uint(size.x);
    if (width == 0u) return 0u;
    uint x = word % width;
    uint y = word / width;
    if (y >= uint(size.y)) return 0u;
    vec4 encoded = texelFetch(source, ivec2(int(x), int(y)), 0);
    return rasterByte(encoded.r)
            | (rasterByte(encoded.g) << 8u)
            | (rasterByte(encoded.b) << 16u)
            | (rasterByte(encoded.a) << 24u);
}

vec3 positionAt(vec2 uv, float depth) {
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    return p.xyz / p.w;
}

int cubeFace(vec3 normal) {
    vec3 a = abs(normal);
    if (a.x >= a.y && a.x >= a.z) return normal.x < 0.0 ? 0 : 1;
    if (a.y >= a.z) return normal.y < 0.0 ? 2 : 3;
    return normal.z < 0.0 ? 4 : 5;
}

vec2 readUv(uint word) {
    return vec2(
            uintBitsToFloat(rawWord(SurfaceSetLut, word)),
            uintBitsToFloat(rawWord(SurfaceSetLut, word + 1u)));
}

vec2 cubeUv(uint faceWord, int face, vec3 localHit) {
    vec2 uv00 = readUv(faceWord + 1u);
    vec2 uv10 = readUv(faceWord + 3u);
    vec2 uv11 = readUv(faceWord + 5u);
    vec2 uv01 = readUv(faceWord + 7u);
    vec2 st;
    if (face < 2) st = vec2(localHit.y, localHit.z);
    else if (face < 4) st = vec2(localHit.z, localHit.x);
    else st = vec2(localHit.x, localHit.y);
    st = clamp(st, vec2(0.0), vec2(1.0));
    return mix(mix(uv00, uv10, st.x), mix(uv01, uv11, st.x), st.y);
}

vec3 rgb24(uint rgb) {
    return vec3(
            float((rgb >> 16u) & 255u),
            float((rgb >> 8u) & 255u),
            float(rgb & 255u)) / 255.0;
}

void main() {
    // Alpha is coverage: zero means this pixel must retain native-lit fallback.
    fragColor = vec4(0.0);

    vec4 identity = texture(VisibleSurfaceIdentity, texCoord);
    uint surfaceSetId = rasterSurfaceSetId(identity);
    if (surfaceSetId == 0u || rawWord(SurfaceSetLut, 0u) != SURFACE_ABI_VERSION) return;

    float depth = texture(DepthSampler, texCoord).r;
    vec4 packedNormal = texture(NormalSampler, texCoord);
    if (depth <= 0.000001 || packedNormal.a <= 0.0) return;
    vec3 normal = normalize(packedNormal.rgb * 2.0 - 1.0);
    if (any(isnan(normal)) || any(isinf(normal))) return;

    int face = cubeFace(normal);
    uint record = SURFACE_RECORD_BASE + surfaceSetId * SURFACE_RECORD_WORDS;
    uint faceWord = record + 2u + uint(face) * SURFACE_FACE_WORDS;
    uint textureHandle = rawWord(SurfaceSetLut, faceWord);
    if (textureHandle == 0u || rawWord(PbrTextureLut, 0u) != TEXTURE_ABI_VERSION) return;

    uint descriptor = TEXTURE_DESCRIPTOR_BASE + textureHandle * TEXTURE_DESCRIPTOR_WORDS;
    uint firstTexel = rawWord(PbrTextureLut, descriptor);
    uint width = rawWord(PbrTextureLut, descriptor + 1u);
    uint height = rawWord(PbrTextureLut, descriptor + 2u);
    uint flags = rawWord(PbrTextureLut, descriptor + 3u);
    // Animated selection remains a later explicit ABI; do not silently sample frame zero.
    if (width == 0u || height == 0u || (flags & TEXTURE_FLAG_ANIMATED) != 0u) return;

    vec3 p = positionAt(texCoord, depth) + ModelOffset;
    vec3 localHit = fract(p);
    vec2 uv = fract(cubeUv(faceWord, face, localHit));
    uint x = min(width - 1u, uint(floor(uv.x * float(width))));
    uint y = min(height - 1u, uint(floor(uv.y * float(height))));
    uint texel = firstTexel + y * width + x;
    uint argb = rawWord(PbrTextureLut, TEXTURE_TEXEL_POOL_BASE + texel * TEXTURE_TEXEL_WORDS);
    float alpha = float((argb >> 24u) & 255u) / 255.0;
    if (alpha <= 0.0) return;

    uint tint = rawWord(SurfaceSetLut, faceWord + SURFACE_FACE_TINT_WORD);
    vec3 albedo = rgb24(argb) * rgb24(tint);
    fragColor = vec4(albedo, alpha);
}
