package dev.totem.lumen.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.totem.lumen.TotemLumenClient;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;

/**
 * Resource-pack controlled renderer constants uploaded through the stable scene header ABI.
 *
 * <p>Changing this resource changes frame data only. Generated GLSL, SPIR-V cache keys and
 * MoltenVK pipeline identity stay unchanged.</p>
 */
public final class RendererRuntimeTuningRegistry {
    private static final Identifier RESOURCE =
            Identifier.fromNamespaceAndPath("totem-lumen", "renderer_runtime.json");

    public record Values(
            float localLightGain,
            float reflectionSpreadScale,
            float reflectionRoughnessEnergy,
            float reflectionDielectricEnergy,
            float reflectionMetalEnergy,
            float reflectionNormalBias,
            float reflectionDirectionBias,
            int transmissionMaxLayers,
            float transmissionExitEpsilon,
            float transmissionMinScalar,
            float waterTintMix,
            float waterTransmissionScalar,
            float waterFallbackR,
            float waterFallbackG,
            float waterFallbackB,
            float waterMaterialR,
            float waterMaterialG,
            float waterMaterialB,
            float lavaMaterialR,
            float lavaMaterialG,
            float lavaMaterialB,
            float otherFluidR,
            float otherFluidG,
            float otherFluidB,
            float lavaEmissionR,
            float lavaEmissionG,
            float lavaEmissionB,
            float lavaEmissionStrength,
            float localAmbient,
            float surfaceEmissionGain,
            float giDisplayGain
    ) {
        public static final Values DEFAULT = new Values(
                2.4f,
                0.75f,
                0.18f,
                0.85f,
                1.0f,
                0.035f,
                0.01f,
                8,
                0.002f,
                0.0001f,
                0.30f,
                0.94f,
                0.25f, 0.50f, 0.78f,
                0.18f, 0.42f, 0.68f,
                1.00f, 0.24f, 0.025f,
                0.45f, 0.55f, 0.62f,
                1.00f, 0.12f, 0.015f, 1.0f,
                0.045f,
                1.6f,
                1.35f
        );

        public Values {
            localLightGain = finite(localLightGain, 0.0f, 16.0f, "localLightGain");
            reflectionSpreadScale = finite(reflectionSpreadScale, 0.0f, 4.0f, "reflectionSpreadScale");
            reflectionRoughnessEnergy = finite(reflectionRoughnessEnergy, 0.0f, 1.0f, "reflectionRoughnessEnergy");
            reflectionDielectricEnergy = finite(reflectionDielectricEnergy, 0.0f, 4.0f, "reflectionDielectricEnergy");
            reflectionMetalEnergy = finite(reflectionMetalEnergy, 0.0f, 4.0f, "reflectionMetalEnergy");
            reflectionNormalBias = finite(reflectionNormalBias, 0.0f, 0.5f, "reflectionNormalBias");
            reflectionDirectionBias = finite(reflectionDirectionBias, 0.0f, 0.5f, "reflectionDirectionBias");
            transmissionMaxLayers = Math.max(1, Math.min(8, transmissionMaxLayers));
            transmissionExitEpsilon = finite(transmissionExitEpsilon, 0.00001f, 0.1f, "transmissionExitEpsilon");
            transmissionMinScalar = finite(transmissionMinScalar, 0.0f, 1.0f, "transmissionMinScalar");
            waterTintMix = finite(waterTintMix, 0.0f, 1.0f, "waterTintMix");
            waterTransmissionScalar = finite(waterTransmissionScalar, 0.0f, 1.0f, "waterTransmissionScalar");
            waterFallbackR = normalized(waterFallbackR, "waterFallbackR");
            waterFallbackG = normalized(waterFallbackG, "waterFallbackG");
            waterFallbackB = normalized(waterFallbackB, "waterFallbackB");
            waterMaterialR = normalized(waterMaterialR, "waterMaterialR");
            waterMaterialG = normalized(waterMaterialG, "waterMaterialG");
            waterMaterialB = normalized(waterMaterialB, "waterMaterialB");
            lavaMaterialR = normalized(lavaMaterialR, "lavaMaterialR");
            lavaMaterialG = normalized(lavaMaterialG, "lavaMaterialG");
            lavaMaterialB = normalized(lavaMaterialB, "lavaMaterialB");
            otherFluidR = normalized(otherFluidR, "otherFluidR");
            otherFluidG = normalized(otherFluidG, "otherFluidG");
            otherFluidB = normalized(otherFluidB, "otherFluidB");
            lavaEmissionR = normalized(lavaEmissionR, "lavaEmissionR");
            lavaEmissionG = normalized(lavaEmissionG, "lavaEmissionG");
            lavaEmissionB = normalized(lavaEmissionB, "lavaEmissionB");
            lavaEmissionStrength = finite(lavaEmissionStrength, 0.0f, 8.0f, "lavaEmissionStrength");
            localAmbient = finite(localAmbient, 0.0f, 1.0f, "localAmbient");
            surfaceEmissionGain = finite(surfaceEmissionGain, 0.0f, 8.0f, "surfaceEmissionGain");
            giDisplayGain = finite(giDisplayGain, 0.0f, 8.0f, "giDisplayGain");
        }

        private static float normalized(float value, String name) {
            return finite(value, 0.0f, 1.0f, name);
        }

        private static float finite(float value, float min, float max, String name) {
            if (!Float.isFinite(value) || value < min || value > max) {
                throw new IllegalArgumentException(
                        name + " must be finite and in [" + min + ", " + max + "]"
                );
            }
            return value;
        }
    }

    private static volatile Values current = Values.DEFAULT;
    private static volatile boolean loaded;
    private static volatile long revision;

    private RendererRuntimeTuningRegistry() {
    }

    public static void ensureLoaded(ResourceManager resources) {
        if (loaded) return;
        synchronized (RendererRuntimeTuningRegistry.class) {
            if (loaded) return;
            current = load(resources);
            loaded = true;
            revision++;
            TotemLumenClient.LOGGER.info(
                    "Renderer runtime tuning loaded: revision={}, transmissionLayers={}, localLightGain={}, reflectionSpread={}",
                    revision,
                    current.transmissionMaxLayers(),
                    current.localLightGain(),
                    current.reflectionSpreadScale()
            );
        }
    }

    public static Values current() {
        return current;
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void invalidate() {
        current = Values.DEFAULT;
        loaded = false;
        revision++;
    }

    private static Values load(ResourceManager resources) {
        try {
            var resource = resources.getResource(RESOURCE);
            if (resource.isEmpty()) return Values.DEFAULT;
            try (Reader reader = resource.get().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("renderer_runtime.json root must be an object");
                }
                JsonObject object = parsed.getAsJsonObject();
                Values d = Values.DEFAULT;
                float[] waterFallback = readVec3(object, "water_fallback_tint",
                        d.waterFallbackR(), d.waterFallbackG(), d.waterFallbackB());
                float[] waterMaterial = readVec3(object, "water_material_color",
                        d.waterMaterialR(), d.waterMaterialG(), d.waterMaterialB());
                float[] lavaMaterial = readVec3(object, "lava_material_color",
                        d.lavaMaterialR(), d.lavaMaterialG(), d.lavaMaterialB());
                float[] otherFluid = readVec3(object, "other_fluid_color",
                        d.otherFluidR(), d.otherFluidG(), d.otherFluidB());
                float[] lavaEmission = readVec3(object, "lava_emission_color",
                        d.lavaEmissionR(), d.lavaEmissionG(), d.lavaEmissionB());
                return new Values(
                        readFloat(object, "local_light_gain", d.localLightGain()),
                        readFloat(object, "reflection_spread_scale", d.reflectionSpreadScale()),
                        readFloat(object, "reflection_roughness_energy", d.reflectionRoughnessEnergy()),
                        readFloat(object, "reflection_dielectric_energy", d.reflectionDielectricEnergy()),
                        readFloat(object, "reflection_metal_energy", d.reflectionMetalEnergy()),
                        readFloat(object, "reflection_normal_bias", d.reflectionNormalBias()),
                        readFloat(object, "reflection_direction_bias", d.reflectionDirectionBias()),
                        readInt(object, "transmission_max_layers", d.transmissionMaxLayers()),
                        readFloat(object, "transmission_exit_epsilon", d.transmissionExitEpsilon()),
                        readFloat(object, "transmission_min_scalar", d.transmissionMinScalar()),
                        readFloat(object, "water_tint_mix", d.waterTintMix()),
                        readFloat(object, "water_transmission_scalar", d.waterTransmissionScalar()),
                        waterFallback[0], waterFallback[1], waterFallback[2],
                        waterMaterial[0], waterMaterial[1], waterMaterial[2],
                        lavaMaterial[0], lavaMaterial[1], lavaMaterial[2],
                        otherFluid[0], otherFluid[1], otherFluid[2],
                        lavaEmission[0], lavaEmission[1], lavaEmission[2],
                        readFloat(object, "lava_emission_strength", d.lavaEmissionStrength()),
                        readFloat(object, "local_ambient", d.localAmbient()),
                        readFloat(object, "surface_emission_gain", d.surfaceEmissionGain()),
                        readFloat(object, "gi_display_gain", d.giDisplayGain())
                );
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to load {}; using stable renderer defaults",
                    RESOURCE,
                    failure
            );
            return Values.DEFAULT;
        }
    }

    private static float readFloat(JsonObject object, String key, float fallback) {
        JsonElement element = object.get(key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return element.getAsFloat();
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        return element.getAsInt();
    }

    private static float[] readVec3(JsonObject object, String key, float x, float y, float z) {
        JsonElement element = object.get(key);
        if (element == null) return new float[]{x, y, z};
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException(key + " must be an array of three numbers");
        }
        return new float[]{
                element.getAsJsonArray().get(0).getAsFloat(),
                element.getAsJsonArray().get(1).getAsFloat(),
                element.getAsJsonArray().get(2).getAsFloat()
        };
    }
}
