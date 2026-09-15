package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P13 correctness follow-up: Overworld sky ambient must respect geometry occlusion.
 *
 * <p>The first environment-lighting baseline evaluated sky radiance directly from the surface
 * normal. That made fully enclosed rooms brighten and darken with the Overworld clock even though
 * the roof correctly blocked the directional sun. This patch adds a conservative world-up sky
 * visibility ray before applying Overworld sky ambient. P15 is deliberately chained after this
 * transform so that the same sky visibility path can become RGB-transmissive through glass.</p>
 */
final class P13SkyOcclusionPatch {
    private P13SkyOcclusionPatch() {
    }

    static String apply(String source) {
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

        TotemLumenClient.LOGGER.info(
                "P13 skylight occlusion active: overworldSkyVisibilityRay=world-up, sealedRoomTimeLeakFix=true"
        );
        return P15GlassTransmissionPatch.apply(source);
    }
}
