package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuPbrTextureScene;

/**
 * P18C shared LabPBR surface evaluation layered after P15 + runtime shadow transforms.
 *
 * <p>The ray-hit ABI remains unchanged. Textured surface identity is reconstructed only for accepted
 * hits, keeping P14E/P17 tracing contracts stable while giving camera/GI/local-light/P16 shading one
 * canonical material sample.</p>
 */
final class P18LabPbrShadingPatch {
    private P18LabPbrShadingPatch() {
    }

    static String apply(String source) {
        String environmentMarker = "vec3 p13EnvironmentSurfaceRadiance(\n";
        if (!source.contains(environmentMarker)) {
            throw new IllegalStateException("P18C shader marker missing: environment surface radiance");
        }

        String declarationMarker = "vec3 directionalSampleDirection(uint sampleIndex) {";
        if (!source.contains(declarationMarker)) {
            throw new IllegalStateException("P18C shader marker missing: material declaration anchor");
        }

        String declarations = ("""
                const uint P18_TEXTURE_FLAG_HAS_NORMAL = %du;
                const uint P18_TEXTURE_FLAG_HAS_SPECULAR = %du;

                struct P18SurfaceSample {
                    uint textured;
                    uint textureHandle;
                    vec2 uv;
                    vec3 albedo;
                    vec3 geometricNormal;
                    vec3 normal;
                    float ao;
                    float roughness;
                    float metallic;
                    vec3 f0;
                    vec3 emission;
                };

                P18SurfaceSample p18ResolveSurface(
                        HitResult hit,
                        vec3 rayOrigin,
                        vec3 rayDirection
                );

                """).formatted(
                GpuPbrTextureScene.FLAG_HAS_NORMAL,
                GpuPbrTextureScene.FLAG_HAS_SPECULAR
        );
        source = source.replace(declarationMarker, declarations + declarationMarker);

        String helpers = """
                vec3 p18ArgbRgb(uint argb) {
                    return vec3(
                        float((argb >> 16u) & 255u),
                        float((argb >> 8u) & 255u),
                        float(argb & 255u)
                    ) / 255.0;
                }

                uint p18TextureDescriptor(uint textureHandle) {
                    if (textureHandle == 0u || textureHandle > 4095u) return 0u;
                    uint textureBase = p18TextureSceneBase();
                    if (scene.data[textureBase] != P18_TEXTURE_ABI_VERSION) return 0u;
                    uint descriptor = textureBase + P18_TEXTURE_DESCRIPTOR_BASE
                            + textureHandle * P18_TEXTURE_DESCRIPTOR_WORDS;
                    if (scene.data[descriptor + 1u] == 0u || scene.data[descriptor + 2u] == 0u) {
                        return 0u;
                    }
                    return descriptor;
                }

                uint p18SampleTextureWord(uint textureHandle, vec2 uv, uint lane) {
                    uint descriptor = p18TextureDescriptor(textureHandle);
                    if (descriptor == 0u) return 0u;

                    uint textureBase = p18TextureSceneBase();
                    uint texelOffset = scene.data[descriptor];
                    uint width = scene.data[descriptor + 1u];
                    uint height = scene.data[descriptor + 2u];
                    vec2 wrapped = fract(uv);
                    uint x = min(width - 1u, uint(floor(wrapped.x * float(width))));
                    uint y = min(height - 1u, uint(floor(wrapped.y * float(height))));
                    uint texelIndex = texelOffset + y * width + x;
                    return scene.data[
                        textureBase + P18_TEXTURE_TEXEL_POOL_BASE
                        + texelIndex * P18_TEXTURE_TEXEL_WORDS
                        + lane
                    ];
                }

                vec3 p18HardcodedMetalF0(uint metalCode, vec3 albedo) {
                    if (metalCode == 230u) return vec3(0.531229, 0.512357, 0.495829);
                    if (metalCode == 231u) return vec3(0.944230, 0.776102, 0.373402);
                    if (metalCode == 232u) return vec3(0.912298, 0.913851, 0.919681);
                    if (metalCode == 233u) return vec3(0.555597, 0.554537, 0.554779);
                    if (metalCode == 234u) return vec3(0.925952, 0.720902, 0.504154);
                    if (metalCode == 235u) return vec3(0.632484, 0.625937, 0.641479);
                    if (metalCode == 236u) return vec3(0.678849, 0.642401, 0.588410);
                    if (metalCode == 237u) return vec3(0.962000, 0.949468, 0.922116);
                    // 238..255 have no standardized hardcoded constants in the current table.
                    // Treat them as custom-metal-compatible and use albedo as conductor F0.
                    return albedo;
                }

                void p18FallbackBasis(
                        vec3 normal,
                        out vec3 tangent,
                        out vec3 bitangent
                ) {
                    vec3 helper = abs(normal.y) < 0.95
                            ? vec3(0.0, 1.0, 0.0)
                            : vec3(1.0, 0.0, 0.0);
                    tangent = normalize(cross(helper, normal));
                    bitangent = normalize(cross(normal, tangent));
                }

                void p18TriangleBasis(
                        vec3 a,
                        vec3 b,
                        vec3 c,
                        vec2 uvA,
                        vec2 uvB,
                        vec2 uvC,
                        vec3 geometricNormal,
                        out vec3 tangent,
                        out vec3 bitangent
                ) {
                    vec3 edge1 = b - a;
                    vec3 edge2 = c - a;
                    vec2 deltaUv1 = uvB - uvA;
                    vec2 deltaUv2 = uvC - uvA;
                    float determinant = deltaUv1.x * deltaUv2.y - deltaUv1.y * deltaUv2.x;
                    if (abs(determinant) < 0.0000001) {
                        p18FallbackBasis(geometricNormal, tangent, bitangent);
                        return;
                    }

                    float inverse = 1.0 / determinant;
                    tangent = edge1 * (deltaUv2.y * inverse)
                            - edge2 * (deltaUv1.y * inverse);
                    bitangent = edge2 * (deltaUv1.x * inverse)
                            - edge1 * (deltaUv2.x * inverse);
                    if (length(tangent) < 0.000001 || length(bitangent) < 0.000001) {
                        p18FallbackBasis(geometricNormal, tangent, bitangent);
                        return;
                    }
                    tangent = normalize(tangent);
                    bitangent = normalize(bitangent);
                }

                bool p18ResolveTriangleAtDistance(
                        vec3 origin,
                        vec3 direction,
                        vec3 a,
                        vec3 b,
                        vec3 c,
                        vec2 uvA,
                        vec2 uvB,
                        vec2 uvC,
                        float targetDistance,
                        out float distanceError,
                        out vec2 surfaceUv,
                        out vec3 tangent,
                        out vec3 bitangent,
                        out vec3 geometricNormal
                ) {
                    vec3 edge1 = b - a;
                    vec3 edge2 = c - a;
                    vec3 p = cross(direction, edge2);
                    float determinant = dot(edge1, p);
                    if (abs(determinant) < 0.0000001) return false;

                    float inverseDeterminant = 1.0 / determinant;
                    vec3 tvec = origin - a;
                    float u = dot(tvec, p) * inverseDeterminant;
                    if (u < -0.00001 || u > 1.00001) return false;
                    vec3 q = cross(tvec, edge1);
                    float v = dot(direction, q) * inverseDeterminant;
                    if (v < -0.00001 || u + v > 1.00001) return false;

                    float candidateDistance = dot(edge2, q) * inverseDeterminant;
                    distanceError = abs(candidateDistance - targetDistance);
                    if (distanceError > 0.003) return false;

                    geometricNormal = normalize(cross(edge1, edge2));
                    if (dot(geometricNormal, direction) > 0.0) geometricNormal = -geometricNormal;
                    surfaceUv = uvA * (1.0 - u - v) + uvB * u + uvC * v;
                    p18TriangleBasis(
                        a, b, c, uvA, uvB, uvC, geometricNormal, tangent, bitangent
                    );
                    return true;
                }

                bool p18ResolveModelSurface(
                        HitResult hit,
                        vec3 rayOrigin,
                        vec3 rayDirection,
                        uint meshId,
                        out uint textureHandle,
                        out vec2 surfaceUv,
                        out vec3 tangent,
                        out vec3 bitangent,
                        out vec3 geometricNormal
                ) {
                    if (meshId == 0u || meshId > 4095u) return false;
                    uint descriptor = p14ModelDescriptorBase() + meshId * 2u;
                    uint firstQuad = scene.data[descriptor];
                    uint quadCount = min(scene.data[descriptor + 1u], 512u);
                    if (quadCount == 0u) return false;

                    bool found = false;
                    float bestError = 1.0e30;
                    vec3 blockOrigin = vec3(hit.voxel);
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
                        uint candidateHandle = scene.data[quadWord + 20u];

                        float candidateError;
                        vec2 candidateUv;
                        vec3 candidateTangent;
                        vec3 candidateBitangent;
                        vec3 candidateNormal;

                        if (p18ResolveTriangleAtDistance(
                                rayOrigin, rayDirection,
                                v0, v1, v2,
                                uv0, uv1, uv2,
                                hit.distance,
                                candidateError,
                                candidateUv,
                                candidateTangent,
                                candidateBitangent,
                                candidateNormal
                        ) && candidateError < bestError) {
                            bestError = candidateError;
                            textureHandle = candidateHandle;
                            surfaceUv = candidateUv;
                            tangent = candidateTangent;
                            bitangent = candidateBitangent;
                            geometricNormal = candidateNormal;
                            found = true;
                        }

                        if (p18ResolveTriangleAtDistance(
                                rayOrigin, rayDirection,
                                v0, v2, v3,
                                uv0, uv2, uv3,
                                hit.distance,
                                candidateError,
                                candidateUv,
                                candidateTangent,
                                candidateBitangent,
                                candidateNormal
                        ) && candidateError < bestError) {
                            bestError = candidateError;
                            textureHandle = candidateHandle;
                            surfaceUv = candidateUv;
                            tangent = candidateTangent;
                            bitangent = candidateBitangent;
                            geometricNormal = candidateNormal;
                            found = true;
                        }
                    }
                    return found;
                }

                void p18CubeBasis(
                        uint surfaceSetId,
                        int face,
                        out vec3 tangent,
                        out vec3 bitangent,
                        out vec3 geometricNormal
                ) {
                    float side = (face & 1) != 0 ? 1.0 : 0.0;
                    vec3 p00;
                    vec3 p10;
                    vec3 p11;

                    if (face < 2) {
                        geometricNormal = face == 0
                                ? vec3(-1.0, 0.0, 0.0)
                                : vec3(1.0, 0.0, 0.0);
                        p00 = vec3(side, 0.0, 0.0);
                        p10 = vec3(side, 1.0, 0.0);
                        p11 = vec3(side, 1.0, 1.0);
                    } else if (face < 4) {
                        geometricNormal = face == 2
                                ? vec3(0.0, -1.0, 0.0)
                                : vec3(0.0, 1.0, 0.0);
                        p00 = vec3(0.0, side, 0.0);
                        p10 = vec3(0.0, side, 1.0);
                        p11 = vec3(1.0, side, 1.0);
                    } else {
                        geometricNormal = face == 4
                                ? vec3(0.0, 0.0, -1.0)
                                : vec3(0.0, 0.0, 1.0);
                        p00 = vec3(0.0, 0.0, side);
                        p10 = vec3(1.0, 0.0, side);
                        p11 = vec3(1.0, 1.0, side);
                    }

                    uint surfaceBase = p18SurfaceSceneBase();
                    uint record = surfaceBase + P18_SURFACE_RECORD_BASE
                            + surfaceSetId * P18_SURFACE_RECORD_WORDS;
                    uint faceWord = record + 2u + uint(face) * P18_SURFACE_FACE_WORDS;
                    vec2 uv00 = p18ReadUv(faceWord + 1u);
                    vec2 uv10 = p18ReadUv(faceWord + 3u);
                    vec2 uv11 = p18ReadUv(faceWord + 5u);
                    p18TriangleBasis(
                        p00,
                        p10,
                        p11,
                        uv00,
                        uv10,
                        uv11,
                        geometricNormal,
                        tangent,
                        bitangent
                    );
                }

                P18SurfaceSample p18ResolveSurface(
                        HitResult hit,
                        vec3 rayOrigin,
                        vec3 rayDirection
                ) {
                    P18SurfaceSample surface;
                    surface.textured = 0u;
                    surface.textureHandle = 0u;
                    surface.uv = vec2(0.0);
                    surface.albedo = materialColor(hit.materialId);
                    surface.geometricNormal = resolvedSurfaceNormal(hit, rayDirection);
                    surface.normal = surface.geometricNormal;
                    surface.ao = 1.0;
                    surface.roughness = 0.80;
                    surface.metallic = 0.0;
                    surface.f0 = vec3(0.04);
                    vec4 baselineEmission = materialEmission(hit.materialId);
                    surface.emission = baselineEmission.rgb * baselineEmission.a * 1.6;

                    // P14E/P17 synthetic materials intentionally keep their dedicated optics.
                    if (hit.materialId >= 0xFFF0u) return surface;

                    uint geometryCode = geometryAt(hit.voxel);
                    uint family = geometryCode & 0xF000u;
                    uint params = geometryCode & 0x0FFFu;
                    vec3 tangent;
                    vec3 bitangent;
                    bool resolved = false;

                    if (family == 0xB000u) {
                        vec2 fallback = p18CubeFallbackSurfaceProperties(params);
                        surface.roughness = fallback.x;
                        surface.metallic = fallback.y;
                        surface.f0 = mix(vec3(0.04), surface.albedo, surface.metallic);

                        int face = p18CubeFaceIndex(hit.normal);
                        if (face >= 0) {
                            vec3 hitPoint = rayOrigin + rayDirection * hit.distance;
                            vec3 localHit = hitPoint - vec3(hit.voxel);
                            surface.textureHandle = p18CubeTextureHandle(params, face);
                            surface.uv = p18CubeFaceUv(params, face, localHit);
                            p18CubeBasis(
                                params,
                                face,
                                tangent,
                                bitangent,
                                surface.geometricNormal
                            );
                            surface.normal = surface.geometricNormal;
                            resolved = surface.textureHandle > 0u;
                        }
                    } else if (family == 0xA000u) {
                        resolved = p18ResolveModelSurface(
                            hit,
                            rayOrigin,
                            rayDirection,
                            params,
                            surface.textureHandle,
                            surface.uv,
                            tangent,
                            bitangent,
                            surface.geometricNormal
                        );
                        surface.normal = surface.geometricNormal;
                    }

                    if (!resolved) return surface;
                    uint descriptor = p18TextureDescriptor(surface.textureHandle);
                    if (descriptor == 0u) return surface;

                    surface.textured = 1u;
                    uint flags = scene.data[descriptor + 3u];
                    uint albedoArgb = p18SampleTextureWord(surface.textureHandle, surface.uv, 0u);
                    surface.albedo = p18ArgbRgb(albedoArgb);

                    if ((flags & P18_TEXTURE_FLAG_HAS_NORMAL) != 0u) {
                        uint normalArgb = p18SampleTextureWord(surface.textureHandle, surface.uv, 1u);
                        float normalX = float((normalArgb >> 16u) & 255u) / 255.0 * 2.0 - 1.0;
                        float normalY = 1.0 - float((normalArgb >> 8u) & 255u) / 255.0 * 2.0;
                        float normalZ = sqrt(max(
                            0.0,
                            1.0 - normalX * normalX - normalY * normalY
                        ));
                        vec3 tangentNormal = vec3(normalX, normalY, normalZ);
                        surface.normal = normalize(
                            tangent * tangentNormal.x
                            + bitangent * tangentNormal.y
                            + surface.geometricNormal * tangentNormal.z
                        );
                        surface.ao = float(normalArgb & 255u) / 255.0;
                    }

                    if ((flags & P18_TEXTURE_FLAG_HAS_SPECULAR) != 0u) {
                        uint specularArgb = p18SampleTextureWord(surface.textureHandle, surface.uv, 2u);
                        float smoothness = float((specularArgb >> 16u) & 255u) / 255.0;
                        surface.roughness = (1.0 - smoothness) * (1.0 - smoothness);

                        uint reflectance = (specularArgb >> 8u) & 255u;
                        if (reflectance <= 229u) {
                            surface.metallic = 0.0;
                            surface.f0 = vec3(float(reflectance) / 255.0);
                        } else {
                            surface.metallic = 1.0;
                            surface.f0 = p18HardcodedMetalF0(reflectance, surface.albedo);
                        }

                        uint emissive = (specularArgb >> 24u) & 255u;
                        if (emissive > 0u && emissive < 255u) {
                            float emissionStrength = float(emissive) / 254.0;
                            surface.emission += surface.albedo * (emissionStrength * 1.6);
                        }
                    }

                    return surface;
                }

                """;
        source = source.replace(environmentMarker, helpers + environmentMarker);

        source = replaceRequiredOnce(
                source,
                """
                    vec3 normal = resolvedSurfaceNormal(hit, rayDirection);
                    vec3 albedo = materialColor(hit.materialId);
                    vec4 emission = materialEmission(hit.materialId);
                    vec3 emitted = emission.rgb * emission.a * 1.6;
                """,
                """
                    P18SurfaceSample p18Surface = p18ResolveSurface(hit, rayOrigin, rayDirection);
                    vec3 normal = p18Surface.normal;
                    vec3 albedo = p18Surface.albedo * (1.0 - p18Surface.metallic);
                    vec3 emitted = p18Surface.emission;
                """,
                "environment material sample"
        );

        source = replaceRequiredOnce(
                source,
                "vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));",
                "vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p18Surface.ao) "
                        + "* p13SkyVisibility(hitPoint, normal);",
                "environment AO"
        );
        source = patchEnvironmentAmbientAo(source);

        source = patchGiIndirect(source);
        source = patchLocalLight(source);
        source = patchGiComposite(source);

        TotemLumenClient.LOGGER.info(
                "P18C LabPBR shading active: albedo=true, normal=true, ao=true, roughness=true, "
                        + "dielectricF0=true, hardcodedMetals=230..237, customMetalFallback=true, "
                        + "emission=true"
        );
        return source;
    }

    private static String patchGiIndirect(String source) {
        String startMarker = "vec3 p13OneBounceIndirectRgb(\n";
        String endMarker = "vec3 giIndirectCurrentRgb(\n";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("P18C shader marker missing: P13 GI span");
        }

        String gi = source.substring(start, end);
        gi = replaceRequiredOnce(
                gi,
                "vec3 primaryNormal = resolvedSurfaceNormal(primaryHit, primaryDirection);",
                "P18SurfaceSample p18PrimarySurface = p18ResolveSurface(\n"
                        + "        primaryHit, primaryOrigin, primaryDirection\n"
                        + "    );\n"
                        + "    vec3 primaryNormal = p18PrimarySurface.normal;",
                "P13 GI primary normal"
        );
        gi = replaceRequiredOnce(
                gi,
                "return materialColor(primaryHit.materialId) * incomingRadiance * GI_STRENGTH;",
                "return p18PrimarySurface.albedo\n"
                        + "            * (1.0 - p18PrimarySurface.metallic)\n"
                        + "            * p18PrimarySurface.ao\n"
                        + "            * incomingRadiance\n"
                        + "            * GI_STRENGTH;",
                "P13 GI primary material"
        );
        return source.substring(0, start) + gi + source.substring(end);
    }

    private static String patchEnvironmentAmbientAo(String source) {
        String startMarker = "vec3 p13EnvironmentSurfaceRadiance(\n";
        String endMarker = "vec3 p13OneBounceIndirectRgb(\n";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("P18C shader marker missing: environment span for AO");
        }

        String environment = source.substring(start, end);
        String facingNeedle = " * facing + emitted;";
        int firstFacing = environment.indexOf(facingNeedle);
        int secondFacing = firstFacing < 0
                ? -1
                : environment.indexOf(facingNeedle, firstFacing + facingNeedle.length());
        int thirdFacing = secondFacing < 0
                ? -1
                : environment.indexOf(facingNeedle, secondFacing + facingNeedle.length());
        if (firstFacing < 0 || secondFacing < 0 || thirdFacing >= 0) {
            throw new IllegalStateException(
                    "P18C shader marker missing/ambiguous: dimension ambient facing returns"
            );
        }

        environment = environment.substring(0, firstFacing)
                + " * (facing * p18Surface.ao) + emitted;"
                + environment.substring(firstFacing + facingNeedle.length());
        secondFacing = environment.indexOf(
                facingNeedle,
                firstFacing + " * (facing * p18Surface.ao) + emitted;".length()
        );
        if (secondFacing < 0) {
            throw new IllegalStateException("P18C shader marker missing: second ambient facing return");
        }
        environment = environment.substring(0, secondFacing)
                + " * (facing * p18Surface.ao) + emitted;"
                + environment.substring(secondFacing + facingNeedle.length());

        String fallbackNeedle = "return albedo * vec3(0.060, 0.070, 0.090) + emitted;";
        int fallback = environment.indexOf(fallbackNeedle);
        if (fallback < 0) {
            throw new IllegalStateException("P18C shader marker missing: fallback ambient return");
        }
        environment = environment.substring(0, fallback)
                + "return albedo * vec3(0.060, 0.070, 0.090) * p18Surface.ao + emitted;"
                + environment.substring(fallback + fallbackNeedle.length());

        return source.substring(0, start) + environment + source.substring(end);
    }

    private static String patchLocalLight(String source) {
        String startMarker = "uint localLightColor(";
        String endMarker = "uint giCurrentColor(";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("P18C shader marker missing: localLightColor span");
        }

        String local = source.substring(start, end);
        local = replaceRequiredOnce(
                local,
                """
                    int slot = sectionSlotForVoxel(hit.voxel);
                    vec4 surfaceEmission = emissiveMode ? materialEmission(hit.materialId) : vec4(0.0);
                    vec3 emitted = surfaceEmission.rgb * surfaceEmission.a * 1.6;
                """,
                """
                    int slot = sectionSlotForVoxel(hit.voxel);
                    P18SurfaceSample p18Surface = p18ResolveSurface(
                        hit, primaryOrigin, primaryDirection
                    );
                    vec3 p18Diffuse = p18Surface.albedo * (1.0 - p18Surface.metallic);
                    vec3 emitted = emissiveMode ? p18Surface.emission : vec3(0.0);
                """,
                "local light material"
        );
        local = replaceRequiredOnce(
                local,
                "return packRgba(materialColor(hit.materialId) * 0.05 + emitted, 255u);",
                "return packRgba(p18Diffuse * (0.05 * p18Surface.ao) + emitted, 255u);",
                "local light no-section fallback"
        );
        local = replaceRequiredOnce(
                local,
                "vec3 surfaceNormal = resolvedSurfaceNormal(hit, primaryDirection);",
                "vec3 surfaceNormal = p18Surface.normal;",
                "local light normal"
        );
        local = replaceRequiredOnce(
                local,
                "vec3 lighting = vec3(0.045);",
                "vec3 lighting = vec3(0.045 * p18Surface.ao);",
                "local light ambient AO"
        );
        local = replaceRequiredOnce(
                local,
                "return packRgba(materialColor(hit.materialId) * lighting + emitted, 255u);",
                "return packRgba(p18Diffuse * lighting + emitted, 255u);",
                "local light output"
        );
        return source.substring(0, start) + local + source.substring(end);
    }

    private static String patchGiComposite(String source) {
        String startMarker = "uint giCompositeFromIndirect(";
        int start = source.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalStateException("P18C shader marker missing: GI composite");
        }
        int end = source.indexOf("void main() {", start);
        if (end < 0) {
            throw new IllegalStateException("P18C shader marker missing: main after GI composite");
        }

        String composite = source.substring(start, end);
        composite = replaceRequiredOnce(
                composite,
                """
                    vec4 emission = materialEmission(hit.materialId);
                    vec3 emitted = emission.rgb * emission.a * 1.6;
                    vec3 localBase = materialColor(hit.materialId) * 0.045 + emitted;
                """,
                """
                    P18SurfaceSample p18Surface = p18ResolveSurface(
                        hit, primaryOrigin, primaryDirection
                    );
                    vec3 emitted = p18Surface.emission;
                    vec3 localBase = p18Surface.albedo
                            * (1.0 - p18Surface.metallic)
                            * (0.045 * p18Surface.ao)
                            + emitted;
                """,
                "GI composite local base"
        );
        return source.substring(0, start) + composite + source.substring(end);
    }

    private static String replaceRequiredOnce(
            String source,
            String oldText,
            String newText,
            String label
    ) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P18C shader marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P18C shader marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
