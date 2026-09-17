package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/** P14E-D optical baseline layered after exact fluid tracing exists. */
final class P14EFluidOpticsPatch {
    private P14EFluidOpticsPatch() {
    }

    static String apply(String source) {
        source = patchMaterialFunctions(source);
        source = patchP15WaterTransmission(source);
        source = patchP16WaterReflection(source);
        TotemLumenClient.LOGGER.info(
                "P14E fluid optics active: exactWaterTransmission=true, resolvedWaterTint=true, "
                        + "exactWaterReflection=true, lavaEmission=true, refraction=false, volumetricAbsorption=false"
        );
        return source;
    }

    private static String patchMaterialFunctions(String source) {
        String colorMarker = "vec3 materialColor(uint materialId) {";
        source = replaceRequiredOnce(
                source,
                colorMarker,
                colorMarker + "\n"
                        + "    if (materialId == P14E_WATER_MATERIAL_ID) return vec3(0.18, 0.42, 0.68);\n"
                        + "    if (materialId == P14E_LAVA_MATERIAL_ID) return vec3(1.00, 0.24, 0.025);\n"
                        + "    if (materialId == P14E_OTHER_FLUID_MATERIAL_ID) return vec3(0.45, 0.55, 0.62);",
                "fluid material colors"
        );

        String emissionMarker = "vec4 materialEmission(uint materialId) {";
        source = replaceRequiredOnce(
                source,
                emissionMarker,
                emissionMarker + "\n"
                        + "    if (materialId == P14E_LAVA_MATERIAL_ID) return vec4(1.00, 0.12, 0.015, 1.0);\n"
                        + "    if (materialId == P14E_WATER_MATERIAL_ID || materialId == P14E_OTHER_FLUID_MATERIAL_ID) return vec4(0.0);",
                "fluid material emission"
        );
        return source;
    }

    private static String patchP15WaterTransmission(String source) {
        String traceMarker = "P15TraceResult p15TraceFiltered(vec3 origin, vec3 direction, float maxDistance) {";
        if (!source.contains(traceMarker)) {
            throw new IllegalStateException("P14E fluid optics marker missing: p15TraceFiltered");
        }

        String helper = """
                vec3 p14eResolvedFluidTint(HitResult hit) {
                    uint fluidBase = p14eFluidSceneBase();
                    uint fluidIndex = p14eFindFluidCell(fluidBase, hit.voxel);
                    if (fluidIndex == P14E_INVALID_INDEX) return vec3(0.25, 0.50, 0.78);
                    uint descriptor = fluidBase + P14E_CELL_DESCRIPTOR_BASE
                            + fluidIndex * P14E_CELL_DESCRIPTOR_WORDS;
                    uint firstQuad = scene.data[descriptor + 5u];
                    uint quadWord = fluidBase + P14E_QUAD_POOL_BASE
                            + firstQuad * P14E_QUAD_WORDS;
                    uint argb = scene.data[quadWord + 12u];
                    vec3 rgb = vec3(
                        float((argb >> 16u) & 255u),
                        float((argb >> 8u) & 255u),
                        float(argb & 255u)
                    ) / 255.0;
                    return clamp(rgb, vec3(0.0), vec3(1.0));
                }

                vec4 p14eWaterTransmission(HitResult hit) {
                    // Baseline is interface tint/attenuation, not volumetric Beer-Lambert absorption.
                    // Two boundary crossings therefore attenuate more than one underwater->air exit.
                    vec3 resolvedTint = p14eResolvedFluidTint(hit);
                    vec3 gentleTint = mix(vec3(1.0), resolvedTint, 0.30);
                    return vec4(gentleTint, 0.94);
                }

                """;
        source = source.replace(traceMarker, helper + traceMarker);

        String oldOptical = """
                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p15GeometryTransmission(geometryCode);
                        if (optical.a <= 0.0001) {
                            candidate.distance += traveled;
                            result.hit = candidate;
                            return result;
                        }

                        result.transmission *= optical.rgb * optical.a;
                        vec3 hitPoint = cursorOrigin + dir * candidate.distance;
                        float exitDistance = p15VoxelExitDistance(
                            hitPoint + dir * 0.0005,
                            dir,
                            candidate.voxel
                        );
                        float advance = candidate.distance + exitDistance + 0.002;
                """;
        String newOptical = """
                        bool p14eWaterSurface = candidate.materialId == P14E_WATER_MATERIAL_ID;
                        uint geometryCode = geometryAt(candidate.voxel);
                        vec4 optical = p14eWaterSurface
                                ? p14eWaterTransmission(candidate)
                                : p15GeometryTransmission(geometryCode);
                        if (optical.a <= 0.0001) {
                            candidate.distance += traveled;
                            result.hit = candidate;
                            return result;
                        }

                        result.transmission *= optical.rgb * optical.a;
                        float advance;
                        if (p14eWaterSurface) {
                            // Advance only through the exact interface. Jumping to the voxel exit
                            // would incorrectly skip waterlogged block geometry in the same cell.
                            advance = candidate.distance + 0.002;
                        } else {
                            vec3 hitPoint = cursorOrigin + dir * candidate.distance;
                            float exitDistance = p15VoxelExitDistance(
                                hitPoint + dir * 0.0005,
                                dir,
                                candidate.voxel
                            );
                            advance = candidate.distance + exitDistance + 0.002;
                        }
                """;
        return replaceRequiredOnce(source, oldOptical, newOptical, "P15 exact-water interface filtering");
    }

    /** Applies only to the extracted P16 pass, where these markers exist. */
    private static String patchP16WaterReflection(String source) {
        if (!source.contains("vec2 p16SurfaceProperties(HitResult hit) {")) return source;

        String propertyMarker = """
                vec2 p16SurfaceProperties(HitResult hit) {
                    uint geometryCode = geometryAt(hit.voxel);
                """;
        String propertyReplacement = """
                vec2 p16SurfaceProperties(HitResult hit) {
                    if (hit.materialId == P14E_WATER_MATERIAL_ID) return vec2(0.025, 0.0);
                    if (hit.materialId == P14E_LAVA_MATERIAL_ID) return vec2(0.32, 0.0);
                    uint geometryCode = geometryAt(hit.voxel);
                """;
        source = replaceRequiredOnce(
                source,
                propertyMarker,
                propertyReplacement,
                "P16 fluid surface properties"
        );

        String oldPrimary = """
                    P15TraceResult primaryTrace = p15TraceFiltered(
                        origin,
                        direction,
                        uintBitsToFloat(scene.data[7])
                    );
                    if (primaryTrace.hit.hit == 0u) return;

                    vec3 reflected = p16ReflectionRgb(primaryTrace.hit, origin, direction)
                            * primaryTrace.transmission;
                """;
        String newPrimary = """
                    HitResult p14eRawPrimary = traceRay(origin, direction);
                    P15TraceResult primaryTrace = p15TraceFiltered(
                        origin,
                        direction,
                        uintBitsToFloat(scene.data[7])
                    );
                    if (primaryTrace.hit.hit == 0u && p14eRawPrimary.hit == 0u) return;

                    bool p14eReflectWater = p14eRawPrimary.hit != 0u
                            && p14eRawPrimary.materialId == P14E_WATER_MATERIAL_ID;
                    HitResult p14eReflectionSurface = p14eReflectWater
                            ? p14eRawPrimary
                            : primaryTrace.hit;
                    if (p14eReflectionSurface.hit == 0u) return;
                    vec3 reflected = p16ReflectionRgb(p14eReflectionSurface, origin, direction)
                            * (p14eReflectWater ? vec3(1.0) : primaryTrace.transmission);
                """;
        return replaceRequiredOnce(
                source,
                oldPrimary,
                newPrimary,
                "P16 raw exact-water reflection surface"
        );
    }

    private static String replaceRequiredOnce(String source, String oldText, String newText, String label) {
        int first = source.indexOf(oldText);
        if (first < 0) {
            throw new IllegalStateException("P14E fluid optics marker missing: " + label);
        }
        int second = source.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalStateException("P14E fluid optics marker is ambiguous: " + label);
        }
        return source.substring(0, first) + newText + source.substring(first + oldText.length());
    }
}
