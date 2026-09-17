package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P13 correctness follow-up: Overworld sky ambient must respect geometry occlusion.
 *
 * <p>P14C is chained immediately before this stage so every later P13/P15/P16 visibility path sees
 * the same generic block-model geometry. P15 is deliberately chained after the sky transform so
 * the same visibility path can become RGB-transmissive through glass. The deterministic P13
 * starfield is then layered onto the already-transformed shared sky radiance path.</p>
 */
final class P13SkyOcclusionPatch {
    private P13SkyOcclusionPatch() {
    }

    static String apply(String source) {
        source = P14GenericModelMeshPatch.apply(source);

        String functionMarker = "vec3 p13EnvironmentSurfaceRadiance(\n";
        if (!source.contains(functionMarker)) {
            throw new IllegalStateException("P13 skylight occlusion patch marker missing: environment surface function");
        }

        String helper = """
                float p13SkyVisibility(vec3 hitPoint, vec3 normal) {
                    // A single world-up ray is a deliberately conservative baseline: sealed rooms
                    // become sky-dark, while exposed horizontal/vertical surfaces still receive sky.
                    vec3 skyDirection = vec3(0.0, 1.0, 0.0);
                    vec3 skyOrigin = hitPoint + normal * 0.035;
                    HitResult skyBlocker = traceRay(skyOrigin, skyDirection);
                    return skyBlocker.hit == 0u ? 1.0 : 0.0;
                }

                """;
        source = source.replace(functionMarker, helper + functionMarker);

        String oldAmbient = "vec3 skyAmbient = p13SkyRadiance(normal) * 0.62;";
        String newAmbient = "vec3 skyAmbient = p13SkyRadiance(normal) * (0.62 * p13SkyVisibility(hitPoint, normal));";
        if (!source.contains(oldAmbient)) {
            throw new IllegalStateException("P13 skylight occlusion patch marker missing: sky ambient");
        }
        source = source.replace(oldAmbient, newAmbient);

        // P15's helper body is injected later near the P13 environment functions, while
        // localLightColor appears earlier in the monolithic shader. GLSL therefore needs a
        // prototype before that first call site.
        String localLightMarker = "uint localLightColor(";
        if (!source.contains(localLightMarker)) {
            throw new IllegalStateException("P15 forward declaration marker missing: localLightColor");
        }
        source = source.replace(
                localLightMarker,
                "vec3 p15RayTransmission(vec3 origin, vec3 direction, float maxDistance);\n\n"
                        + localLightMarker
        );

        TotemLumenClient.LOGGER.info(
                "P13 skylight occlusion active: overworldSkyVisibilityRay=world-up, sealedRoomTimeLeakFix=true"
        );
        source = P15GlassTransmissionPatch.apply(source);
        return P13StarfieldPatch.apply(source);
    }
}
