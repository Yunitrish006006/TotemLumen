package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;

/**
 * P16 compatibility transform.
 *
 * <p>Alpha 33-35 injected reflection directly into the P12-P15 monolithic GI shader. On Apple
 * Silicon/MoltenVK that made Metal pipeline compilation effectively unbounded. Alpha 36 keeps this
 * transform as a deliberate no-op: P16 now runs in {@link P16MultipassReflection} as an independent
 * compute pass after the base P12-P15 dispatch.</p>
 */
final class P16ReflectionRoughnessPatch {
    static final float REFLECTION_MAX_DISTANCE = 64.0f;

    private P16ReflectionRoughnessPatch() {
    }

    static String apply(String source) {
        TotemLumenClient.LOGGER.info(
                "P16 monolithic shader injection disabled: reflection is a separate compute pass, maxDistance={}",
                REFLECTION_MAX_DISTANCE
        );
        return source;
    }
}
