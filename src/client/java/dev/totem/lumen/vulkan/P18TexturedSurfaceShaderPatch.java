package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuDynamicEntityScene;
import dev.totem.lumen.gpu.GpuFluidScene;
import dev.totem.lumen.gpu.GpuPbrSurfaceSetScene;
import dev.totem.lumen.gpu.GpuPbrTextureScene;

/**
 * P18 textured-surface geometry layer.
 *
 * <p>Runs immediately after P14C generic mesh insertion and before P15 transmission. It keeps the
 * voxel DDA as broad phase, rejects zero-alpha texels before a hit is accepted, and preserves the
 * canonical full-cube AABB path through the 0xB000 textured-cube family.</p>
 */
final class P18TexturedSurfaceShaderPatch {
    private P18TexturedSurfaceShaderPatch() {
    }

    static String apply(String source) {
        source = replaceRequiredOnce(
                source,
                "|| family == 0xA000u;",
                "|| family == 0xA000u\n        || family == 0xB000u;",
                "textured-cube local geometry family"
        );

        String modelBaseMarker = "uint p14ModelDescriptorBase() {";
        if (!source.contains(modelBaseMarker)) {
            throw new IllegalStateException("P18 shader marker missing: P14 model descriptor base");
        }

        String p18Helpers = ("""
                const uint P18_TEXTURE_ABI_VERSION = %du;
                const uint P18_TEXTURE_DESCRIPTOR_WORDS = %du;
                const uint P18_TEXTURE_DESCRIPTOR_BASE = %du;
                const uint P18_TEXTURE_ANIMATION_POOL_BASE = %du;
                const uint P18_TEXTURE_TEXEL_POOL_BASE = %du;
                const uint P18_TEXTURE_TEXEL_WORDS = %du;
                const uint P18_TEXTURE_FLAG_ANIMATED = %du;
                const uint P18_P14_MAX_STORAGE_WORDS = %du;
                const uint P18_P17_MAX_STORAGE_WORDS = %du;
                const uint P18_P14E_MAX_STORAGE_WORDS = %du;
                const uint P18_SURFACE_MAX_STORAGE_WORDS = %du;
                const uint P18_SURFACE_RECORD_WORDS = %du;
                const uint P18_SURFACE_RECORD_BASE = %du;
                const uint P18_SURFACE_FACE_WORDS = %du;

                uint p18SurfaceSceneBase() {
                    uint pixelCount = scene.data[51] * scene.data[52];
                    return scene.data[3] + pixelCount
                            + P18_P14_MAX_STORAGE_WORDS
                            + P18_P17_MAX_STORAGE_WORDS
                            + P18_P14E_MAX_STORAGE_WORDS;
                }

                uint p18TextureSceneBase() {
                    return p18SurfaceSceneBase() + P18_SURFACE_MAX_STORAGE_WORDS;
                }

                uint p18CurrentFrameTexelOffset(uint textureBase, uint descriptor) {
                    uint texelOffset = scene.data[descriptor];
                    uint width = scene.data[descriptor + 1u];
                    uint height = scene.data[descriptor + 2u];
                    uint flags = scene.data[descriptor + 3u];
                    uint timelineLength = scene.data[descriptor + 10u];
                    if ((flags & P18_TEXTURE_FLAG_ANIMATED) == 0u || timelineLength == 0u) {
                        return texelOffset;
                    }

                    uint timelineOffset = scene.data[descriptor + 9u];
                    uint timelineIndex = scene.data[53] %% timelineLength;
                    uint packedFrame = scene.data[
                        textureBase + P18_TEXTURE_ANIMATION_POOL_BASE
                        + timelineOffset + timelineIndex
                    ];
                    return texelOffset + packedFrame * width * height;
                }

                uint p18SampleAlbedoArgb(uint textureHandle, vec2 uv) {
                    if (textureHandle == 0u || textureHandle > 4095u) return 0xFFFFFFFFu;
                    uint textureBase = p18TextureSceneBase();
                    if (scene.data[textureBase] != P18_TEXTURE_ABI_VERSION) return 0xFFFFFFFFu;

                    uint descriptor = textureBase + P18_TEXTURE_DESCRIPTOR_BASE
                            + textureHandle * P18_TEXTURE_DESCRIPTOR_WORDS;
                    uint width = scene.data[descriptor + 1u];
                    uint height = scene.data[descriptor + 2u];
                    if (width == 0u || height == 0u) return 0xFFFFFFFFu;

                    uint texelOffset = p18CurrentFrameTexelOffset(textureBase, descriptor);
                    vec2 wrapped = fract(uv);
                    uint x = min(width - 1u, uint(floor(wrapped.x * float(width))));
                    uint y = min(height - 1u, uint(floor(wrapped.y * float(height))));
                    uint texelIndex = texelOffset + y * width + x;
                    uint word = textureBase + P18_TEXTURE_TEXEL_POOL_BASE
                            + texelIndex * P18_TEXTURE_TEXEL_WORDS;
                    return scene.data[word];
                }

                uint p18AlbedoAlpha(uint textureHandle, vec2 uv) {
                    return (p18SampleAlbedoArgb(textureHandle, uv) >> 24u) & 0xFFu;
                }

                uint p18AlphaHash(uint value) {
                    value ^= value >> 16u;
                    value *= 0x7FEB352Du;
                    value ^= value >> 15u;
                    value *= 0x846CA68Bu;
                    value ^= value >> 16u;
                    return value;
                }

                bool p18AlphaAccept(uint textureHandle, vec2 uv, uint salt) {
                    uint alpha = p18AlbedoAlpha(textureHandle, uv);
                    if (alpha == 0u) return false;
                    if (alpha == 255u) return true;

                    uint textureBase = p18TextureSceneBase();
                    uint descriptor = textureBase + P18_TEXTURE_DESCRIPTOR_BASE
                            + textureHandle * P18_TEXTURE_DESCRIPTOR_WORDS;
                    float alphaCutoff = uintBitsToFloat(scene.data[descriptor + 15u]);
                    if (alphaCutoff > 0.0) {
                        return float(alpha) / 255.0 >= alphaCutoff;
                    }

                    uint state = textureHandle * 0x9E3779B1u
                            ^ floatBitsToUint(uv.x) * 0x85EBCA77u
                            ^ floatBitsToUint(uv.y) * 0xC2B2AE3Du
                            ^ salt
                            ^ scene.data[41] * 0x27D4EB2Du;
                    uint hashed = p18AlphaHash(state);
                    float coverageSample = float(hashed & 0x00FFFFFFu) / 16777216.0;
                    return coverageSample < float(alpha) / 255.0;
                }

                vec2 p18ReadUv(uint wordBase) {
                    return vec2(
                        uintBitsToFloat(scene.data[wordBase]),
                        uintBitsToFloat(scene.data[wordBase + 1u])
                    );
                }

                int p18CubeFaceIndex(ivec3 normal) {
                    if (normal.x < 0) return 0;
                    if (normal.x > 0) return 1;
                    if (normal.y < 0) return 2;
                    if (normal.y > 0) return 3;
                    if (normal.z < 0) return 4;
                    if (normal.z > 0) return 5;
                    return -1;
                }

                vec2 p18CubeFaceUv(uint surfaceSetId, int face, vec3 localHit) {
                    uint surfaceBase = p18SurfaceSceneBase();
                    uint record = surfaceBase + P18_SURFACE_RECORD_BASE
                            + surfaceSetId * P18_SURFACE_RECORD_WORDS;
                    uint faceWord = record + 2u + uint(face) * P18_SURFACE_FACE_WORDS;
                    vec2 uv00 = p18ReadUv(faceWord + 1u);
                    vec2 uv10 = p18ReadUv(faceWord + 3u);
                    vec2 uv11 = p18ReadUv(faceWord + 5u);
                    vec2 uv01 = p18ReadUv(faceWord + 7u);

                    vec2 st;
                    if (face < 2) {
                        st = vec2(localHit.y, localHit.z);
                    } else if (face < 4) {
                        st = vec2(localHit.z, localHit.x);
                    } else {
                        st = vec2(localHit.x, localHit.y);
                    }
                    st = clamp(st, vec2(0.0), vec2(1.0));
                    vec2 low = mix(uv00, uv10, st.x);
                    vec2 high = mix(uv01, uv11, st.x);
                    return mix(low, high, st.y);
                }

                uint p18CubeTextureHandle(uint surfaceSetId, int face) {
                    uint surfaceBase = p18SurfaceSceneBase();
                    uint record = surfaceBase + P18_SURFACE_RECORD_BASE
                            + surfaceSetId * P18_SURFACE_RECORD_WORDS;
                    uint faceWord = record + 2u + uint(face) * P18_SURFACE_FACE_WORDS;
                    return scene.data[faceWord];
                }

                vec2 p18CubeFallbackSurfaceProperties(uint surfaceSetId) {
                    uint surfaceBase = p18SurfaceSceneBase();
                    uint record = surfaceBase + P18_SURFACE_RECORD_BASE
                            + surfaceSetId * P18_SURFACE_RECORD_WORDS;
                    return vec2(
                        uintBitsToFloat(scene.data[record]),
                        uintBitsToFloat(scene.data[record + 1u])
                    );
                }

                void p18TryTexturedCube(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        uint surfaceSetId,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    float candidateDistance;
                    ivec3 candidateNormal;
                    vec3 voxelOrigin = vec3(voxel);
                    if (!p14IntersectAabb(
                            origin,
                            direction,
                            voxelOrigin,
                            voxelOrigin + vec3(1.0),
                            cellEntryDistance,
                            cellExitDistance,
                            candidateDistance,
                            candidateNormal
                    )) {
                        return;
                    }
                    if (found && candidateDistance >= bestDistance) return;

                    int face = p18CubeFaceIndex(candidateNormal);
                    if (face >= 0) {
                        vec3 hitPoint = origin + direction * candidateDistance;
                        vec3 localHit = hitPoint - voxelOrigin;
                        uint textureHandle = p18CubeTextureHandle(surfaceSetId, face);
                        vec2 uv = p18CubeFaceUv(surfaceSetId, face, localHit);
                        uint alphaSalt = uint(voxel.x) * 0x9E3779B1u
                                ^ uint(voxel.y) * 0x85EBCA77u
                                ^ uint(voxel.z) * 0xC2B2AE3Du
                                ^ surfaceSetId * 0x27D4EB2Du
                                ^ uint(face);
                        if (!p18AlphaAccept(textureHandle, uv, alphaSalt)) {
                            return;
                        }
                    }

                    found = true;
                    bestDistance = candidateDistance;
                    bestNormal = candidateNormal;
                }

                """).formatted(
                GpuPbrTextureScene.ABI_VERSION,
                GpuPbrTextureScene.DESCRIPTOR_WORDS_PER_RECORD,
                GpuPbrTextureScene.DESCRIPTOR_BASE_WORD,
                GpuPbrTextureScene.ANIMATION_POOL_BASE_WORD,
                GpuPbrTextureScene.TEXEL_POOL_BASE_WORD,
                GpuPbrTextureScene.TEXEL_WORDS_PER_RECORD,
                GpuPbrTextureScene.FLAG_ANIMATED,
                P14ModelMeshGpuLayout.MAX_STORAGE_WORDS,
                GpuDynamicEntityScene.MAX_STORAGE_WORDS,
                GpuFluidScene.MAX_STORAGE_WORDS,
                GpuPbrSurfaceSetScene.MAX_STORAGE_WORDS,
                GpuPbrSurfaceSetScene.RECORD_WORDS,
                GpuPbrSurfaceSetScene.RECORD_BASE_WORD,
                GpuPbrSurfaceSetScene.FACE_WORDS
        );
        source = source.replace(modelBaseMarker, p18Helpers + modelBaseMarker);

        String triangleStart = "void p14TryTriangle(";
        String meshStart = "void p14TryModelMesh(";
        int triangleIndex = source.indexOf(triangleStart);
        int meshIndex = source.indexOf(meshStart, triangleIndex);
        if (triangleIndex < 0 || meshIndex < 0 || meshIndex <= triangleIndex) {
            throw new IllegalStateException("P18 shader marker missing: P14 triangle/model span");
        }

        String triangle = """
                void p14TryTriangle(
                        vec3 origin,
                        vec3 direction,
                        vec3 a,
                        vec3 b,
                        vec3 c,
                        vec2 uvA,
                        vec2 uvB,
                        vec2 uvC,
                        uint textureHandle,
                        uint alphaSalt,
                        float minDistance,
                        float maxDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    vec3 edge1 = b - a;
                    vec3 edge2 = c - a;
                    vec3 p = cross(direction, edge2);
                    float determinant = dot(edge1, p);
                    if (abs(determinant) < 0.0000001) return;

                    float inverseDeterminant = 1.0 / determinant;
                    vec3 tvec = origin - a;
                    float u = dot(tvec, p) * inverseDeterminant;
                    if (u < -0.00001 || u > 1.00001) return;

                    vec3 q = cross(tvec, edge1);
                    float v = dot(direction, q) * inverseDeterminant;
                    if (v < -0.00001 || u + v > 1.00001) return;

                    float distance = dot(edge2, q) * inverseDeterminant;
                    if (distance < minDistance - 0.00001 || distance > maxDistance + 0.00001) return;
                    if (found && distance >= bestDistance) return;

                    vec2 surfaceUv = uvA * (1.0 - u - v) + uvB * u + uvC * v;
                    if (!p18AlphaAccept(textureHandle, surfaceUv, alphaSalt)) return;

                    vec3 geometricNormal = cross(edge1, edge2);
                    float normalLength = length(geometricNormal);
                    if (normalLength < 0.000001) return;
                    geometricNormal /= normalLength;
                    if (dot(geometricNormal, direction) > 0.0) geometricNormal = -geometricNormal;

                    found = true;
                    bestDistance = max(distance, 0.0);
                    bestNormal = ivec3(round(clamp(geometricNormal, vec3(-1.0), vec3(1.0)) * 32767.0));
                }

                """;
        source = source.substring(0, triangleIndex) + triangle + source.substring(meshIndex);

        meshIndex = source.indexOf(meshStart);
        String geometryStart = "bool p14IntersectVoxelGeometry(";
        int geometryIndex = source.indexOf(geometryStart, meshIndex);
        if (meshIndex < 0 || geometryIndex < 0 || geometryIndex <= meshIndex) {
            throw new IllegalStateException("P18 shader marker missing: P14 model/geometry span");
        }

        String mesh = """
                vec2 p14ModelUv(uint quadWord, uint vertex) {
                    uint word = quadWord + 12u + vertex * 2u;
                    return vec2(
                        uintBitsToFloat(scene.data[word]),
                        uintBitsToFloat(scene.data[word + 1u])
                    );
                }

                void p14TryModelMesh(
                        vec3 origin,
                        vec3 direction,
                        ivec3 voxel,
                        uint meshId,
                        float cellEntryDistance,
                        float cellExitDistance,
                        inout bool found,
                        inout float bestDistance,
                        inout ivec3 bestNormal
                ) {
                    if (meshId == 0u || meshId > 4095u) return;
                    uint descriptor = p14ModelDescriptorBase() + meshId * 2u;
                    uint firstQuad = scene.data[descriptor];
                    uint quadCount = min(scene.data[descriptor + 1u], 512u);
                    if (quadCount == 0u) return;

                    vec3 blockOrigin = vec3(voxel);
                    uint quadPool = p14ModelQuadBase();
                    for (uint quadIndex = 0u; quadIndex < quadCount; quadIndex++) {
                        uint quadWord = quadPool + (firstQuad + quadIndex) * 21u;
                        vec3 v0 = blockOrigin + p14ModelVertex(quadWord);
                        vec3 v1 = blockOrigin + p14ModelVertex(quadWord + 3u);
                        vec3 v2 = blockOrigin + p14ModelVertex(quadWord + 6u);
                        vec3 v3 = blockOrigin + p14ModelVertex(quadWord + 9u);
                        vec2 uv0 = p14ModelUv(quadWord, 0u);
                        vec2 uv1 = p14ModelUv(quadWord, 1u);
                        vec2 uv2 = p14ModelUv(quadWord, 2u);
                        vec2 uv3 = p14ModelUv(quadWord, 3u);
                        uint textureHandle = scene.data[quadWord + 20u];

                        p14TryTriangle(
                            origin, direction, v0, v1, v2,
                            uv0, uv1, uv2, textureHandle,
                            uint(voxel.x) * 0x9E3779B1u
                                    ^ uint(voxel.y) * 0x85EBCA77u
                                    ^ uint(voxel.z) * 0xC2B2AE3Du
                                    ^ quadIndex * 2u,
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                        p14TryTriangle(
                            origin, direction, v0, v2, v3,
                            uv0, uv2, uv3, textureHandle,
                            uint(voxel.x) * 0x9E3779B1u
                                    ^ uint(voxel.y) * 0x85EBCA77u
                                    ^ uint(voxel.z) * 0xC2B2AE3Du
                                    ^ quadIndex * 2u + 1u,
                            cellEntryDistance, cellExitDistance,
                            found, bestDistance, bestNormal
                        );
                    }
                }

                """;
        source = source.substring(0, meshIndex) + mesh + source.substring(geometryIndex);

        String hitMarker = """
                if ((geometryCode & 0xF000u) == 0xA000u) {
                    p14TryModelMesh(
                        origin,
                        direction,
                        voxel,
                        geometryCode & 0x0FFFu,
                        cellEntryDistance,
                        cellExitDistance,
                        found,
                        bestDistance,
                        bestNormal
                    );
                }

                hitDistance = bestDistance;
                """;
        String hitReplacement = """
                if ((geometryCode & 0xF000u) == 0xA000u) {
                    p14TryModelMesh(
                        origin,
                        direction,
                        voxel,
                        geometryCode & 0x0FFFu,
                        cellEntryDistance,
                        cellExitDistance,
                        found,
                        bestDistance,
                        bestNormal
                    );
                }
                if ((geometryCode & 0xF000u) == 0xB000u) {
                    p18TryTexturedCube(
                        origin,
                        direction,
                        voxel,
                        geometryCode & 0x0FFFu,
                        cellEntryDistance,
                        cellExitDistance,
                        found,
                        bestDistance,
                        bestNormal
                    );
                }

                hitDistance = bestDistance;
                """;
        source = replaceRequiredOnce(source, hitMarker, hitReplacement, "textured cube intersection");

        TotemLumenClient.LOGGER.info(
                "P18 textured-surface tracing active: genericMeshAlphaCutout=true, "
                        + "texturedCubeFastPath=true, alphaZeroReject=true, partialAlphaStochasticCoverage=true"
        );
        return source;
    }

    private static String replaceRequiredOnce(
            String source,
            String oldText,
            String newText,
            String label
    ) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P18 textured-surface marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P18 textured-surface marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
