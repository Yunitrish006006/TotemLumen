package dev.totem.lumen.integration;

import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.gameplay.light.DefaultEmissionColors;
import dev.totem.lumen.gameplay.light.EmissionColor;
import dev.totem.lumen.world.LightingWorldRule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import com.mojang.blaze3d.vertex.QuadInstance;
import org.joml.Vector3f;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import java.util.concurrent.atomic.AtomicInteger;

/** Resolves the client-local RGB field for Minecraft's terrain geometry. */
public final class VanillaRgbLighting {
    private static final AtomicInteger DIAGNOSTIC_LOGS = new AtomicInteger();
    private static final AtomicInteger INDIGO_DIAGNOSTIC_LOGS = new AtomicInteger();
    private static final ThreadLocal<float[]> RGB_SAMPLE = ThreadLocal.withInitial(() -> new float[3]);
    private static final Direction[] SAMPLE_DIRECTIONS = Direction.values();
    /** Immutable per-tick snapshot read by parallel terrain-mesh workers. */
    private static volatile SkyPalette skyPalette = new SkyPalette(0.92f, 0.96f, 1.0f, 1.0f, 0.04f);
    private VanillaRgbLighting() {
    }

    public static int surfaceColor(BlockAndTintGetter world, BlockState state, BlockPos pos) {
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB) {
            return 0xFFFFFFFF;
        }

        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int packed = ClientGameplayLightField.packedAtCurrentDimension(pos);
        if (packed != 0) {
            return 0xFF000000
                    | (toChannel(PackedRgbLight.hueRed(packed), 15) << 16)
                    | (toChannel(PackedRgbLight.hueGreen(packed), 15) << 8)
                    | toChannel(PackedRgbLight.hueBlue(packed), 15);
        }

        if (state.getLightEmission() <= 0) {
            return 0xFFFFFFFF;
        }
        LightingWorldRule rule = ClientLightingWorldRules.ruleFor(sourceId);
        EmissionColor color = rule == null
                ? DefaultEmissionColors.forBlock(sourceId, state.getLightEmission())
                : new EmissionColor(rule.emissionR(), rule.emissionG(), rule.emissionB());

        return 0xFF000000
                | (toChannel(color.red()) << 16)
                | (toChannel(color.green()) << 8)
                | toChannel(color.blue());
    }

    /** Writes RGB illumination into terrain vertex color while preserving texture and alpha. */
    public static void applySurfaceTint(
            QuadInstance instance,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Direction faceDirection
    ) {
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB) {
            return;
        }
        if (DIAGNOSTIC_LOGS.get() < 8) {
            int logIndex = DIAGNOSTIC_LOGS.getAndIncrement();
            if (logIndex < 8) {
                dev.totem.lumen.TotemLumen.LOGGER.info(
                        "RGB quad entered: pos={}, face={}, state={}, color=0x{}",
                        pos, faceDirection, BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                        Integer.toHexString(instance.getColor(0))
                );
            }
        }
        int packed = ClientGameplayLightField.packedAtCurrentDimension(pos);
        int skyLight = world.getBrightness(LightLayer.SKY, pos);
        if (faceDirection != null) {
            skyLight = Math.max(
                    skyLight,
                    world.getBrightness(
                            LightLayer.SKY,
                            new BlockPos(
                                    pos.getX() + faceDirection.getStepX(),
                                    pos.getY() + faceDirection.getStepY(),
                                    pos.getZ() + faceDirection.getStepZ()
                            )
                    )
            );
        }
        if (skyLight > 0) {
            ClientGameplayLightField.noteSkyLitSurface(pos);
        }
        float red;
        float green;
        float blue;
        float blockRed;
        float blockGreen;
        float blockBlue;
        if (packed != 0) {
            red = PackedRgbLight.red(packed) / 15.0f;
            green = PackedRgbLight.green(packed) / 15.0f;
            blue = PackedRgbLight.blue(packed) / 15.0f;
            blockRed = red;
            blockGreen = green;
            blockBlue = blue;
        } else if (state.getLightEmission() > 0) {
            String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            LightingWorldRule rule = ClientLightingWorldRules.ruleFor(sourceId);
            EmissionColor color = rule == null
                    ? DefaultEmissionColors.forBlock(sourceId, state.getLightEmission())
                    : new EmissionColor(rule.emissionR(), rule.emissionG(), rule.emissionB());
            red = color.red();
            green = color.green();
            blue = color.blue();
            float emission = state.getLightEmission() / 15.0f;
            blockRed = red * emission;
            blockGreen = green * emission;
            blockBlue = blue * emission;
        } else {
            red = 0.0f;
            green = 0.0f;
            blue = 0.0f;
            blockRed = 0.0f;
            blockGreen = 0.0f;
            blockBlue = 0.0f;
        }

        // Sky light is part of the RGB source model as well. It is not a second white overlay:
        // daylight and moonlight contribute their own colour to the same surface tint as local
        // RGB emitters.
        SkyPalette palette = skyPalette;
        float skyWeight = skyLight / 15.0f * palette.timeFactor();
        float skyRed = palette.red() * skyWeight;
        float skyGreen = palette.green() * skyWeight;
        float skyBlue = palette.blue() * skyWeight;
        float blockGain = ClientLightingWorldRules.tuning().brightnessMultiplier();
        red = blockRed * blockGain + skyRed;
        green = blockGreen * blockGain + skyGreen;
        blue = blockBlue * blockGain + skyBlue;
        float ambient = palette.ambient();
        int rgbLightmap = LightCoordsUtil.FULL_BRIGHT;
        if (DIAGNOSTIC_LOGS.get() < 8) {
            int logIndex = DIAGNOSTIC_LOGS.getAndIncrement();
            if (logIndex < 8) {
                dev.totem.lumen.TotemLumen.LOGGER.info(
                        "RGB quad hook active: pos={}, face={}, packed=0x{}, sky={}, "
                                + "color=0x{}, lightmap=0x{}",
                        pos, faceDirection, Integer.toHexString(packed), skyLight,
                        Integer.toHexString(instance.getColor(0)),
                        Integer.toHexString(rgbLightmap)
                );
            }
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int source = instance.getColor(vertex);
            int tintedRed = litChannel(ARGB.red(source), ambient + red);
            int tintedGreen = litChannel(ARGB.green(source), ambient + green);
            int tintedBlue = litChannel(ARGB.blue(source), ambient + blue);
            instance.setColor(vertex, ARGB.color(
                    ARGB.alpha(source), tintedRed, tintedGreen, tintedBlue
            ));
            // A full-bright lookup is neutral here: all spatial block and sky brightness is
            // already in the RGB vertex channels, so the vanilla scalar lightmap cannot add
            // white block light a second time.
            instance.setLightCoords(vertex, rgbLightmap);
        }
    }

    /** Indigo's native terrain path stores the prepared quad in MutableQuadView, not QuadInstance. */
    public static void applySurfaceTint(
            MutableQuadView quad,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Direction faceDirection
    ) {
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB) {
            return;
        }
        ClientGameplayLightField.LocalLookup light = ClientGameplayLightField.localSampler();
        int packed = smoothedPackedAt(pos, faceDirection, light);
        int skyLight = world.getBrightness(LightLayer.SKY, pos);
        if (faceDirection != null) {
            BlockPos neighbour = new BlockPos(
                    pos.getX() + faceDirection.getStepX(),
                    pos.getY() + faceDirection.getStepY(),
                    pos.getZ() + faceDirection.getStepZ()
            );
            skyLight = Math.max(skyLight, world.getBrightness(LightLayer.SKY, neighbour));
        }
        if (skyLight > 0) {
            ClientGameplayLightField.noteSkyLitSurface(pos);
        }
        float fallbackRed = 0.0f;
        float fallbackGreen = 0.0f;
        float fallbackBlue = 0.0f;
        if (state.getLightEmission() > 0) {
            String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            LightingWorldRule rule = ClientLightingWorldRules.ruleFor(sourceId);
            EmissionColor color = rule == null
                    ? DefaultEmissionColors.forBlock(sourceId, state.getLightEmission())
                    : new EmissionColor(rule.emissionR(), rule.emissionG(), rule.emissionB());
            float emission = state.getLightEmission() / 15.0f;
            fallbackRed = color.red() * emission;
            fallbackGreen = color.green() * emission;
            fallbackBlue = color.blue() * emission;
        }
        SkyPalette palette = skyPalette;
        float skyWeight = skyLight / 15.0f * palette.timeFactor();
        float ambient = palette.ambient();
        float blockGain = ClientLightingWorldRules.tuning().brightnessMultiplier();
        if (INDIGO_DIAGNOSTIC_LOGS.get() < 4 && INDIGO_DIAGNOSTIC_LOGS.getAndIncrement() < 4) {
            dev.totem.lumen.TotemLumenClient.LOGGER.info(
                    "RGB Indigo terrain active: pos={}, packed=0x{}, sky={}, vertexSampling={}",
                    pos, Integer.toHexString(packed), skyLight,
                    Minecraft.getInstance().options.ambientOcclusion().get()
            );
        }
        boolean smooth = Minecraft.getInstance().options.ambientOcclusion().get();
        float[] blockRgb = RGB_SAMPLE.get();
        if (!smooth) {
            int center = light.packedAtCurrentDimension(pos);
            int outside = faceDirection == null ? 0 : light.packedAtCurrentDimension(
                    pos.getX() + faceDirection.getStepX(),
                    pos.getY() + faceDirection.getStepY(),
                    pos.getZ() + faceDirection.getStepZ()
            );
            int flat = PackedRgbLight.componentMax(center, outside);
            blockRgb[0] = PackedRgbLight.red(flat) / 15.0f;
            blockRgb[1] = PackedRgbLight.green(flat) / 15.0f;
            blockRgb[2] = PackedRgbLight.blue(flat) / 15.0f;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            if (smooth) {
                sampleRgbAt(
                        pos.getX() + quad.x(vertex),
                        pos.getY() + quad.y(vertex),
                        pos.getZ() + quad.z(vertex),
                        faceDirection, light, blockRgb
                );
            }
            float blockRed = blockRgb[0];
            float blockGreen = blockRgb[1];
            float blockBlue = blockRgb[2];
            if (blockRed == 0.0f && blockGreen == 0.0f && blockBlue == 0.0f
                    && state.getLightEmission() > 0) {
                blockRed = fallbackRed;
                blockGreen = fallbackGreen;
                blockBlue = fallbackBlue;
            }
            int source = quad.color(vertex);
            quad.color(vertex, ARGB.color(
                    ARGB.alpha(source),
                    litChannel(ARGB.red(source), ambient + blockRed * blockGain + palette.red() * skyWeight),
                    litChannel(ARGB.green(source), ambient + blockGreen * blockGain + palette.green() * skyWeight),
                    litChannel(ARGB.blue(source), ambient + blockBlue * blockGain + palette.blue() * skyWeight)
            ));
            quad.lightmap(vertex, LightCoordsUtil.FULL_BRIGHT);
        }
    }

    /** Samples RGB light at a surface vertex, interpolating effective channels without repacking. */
    private static void sampleRgbAt(
            float worldX, float worldY, float worldZ, Direction face,
            ClientGameplayLightField.LocalLookup light, float[] out
    ) {
        float offsetX = face == null ? 0.0f : face.getStepX() * 0.5f;
        float offsetY = face == null ? 0.0f : face.getStepY() * 0.5f;
        float offsetZ = face == null ? 0.0f : face.getStepZ() * 0.5f;
        float sampleX = worldX + offsetX - 0.5f;
        float sampleY = worldY + offsetY - 0.5f;
        float sampleZ = worldZ + offsetZ - 0.5f;
        int baseX = (int) Math.floor(sampleX);
        int baseY = (int) Math.floor(sampleY);
        int baseZ = (int) Math.floor(sampleZ);
        float fractionX = sampleX - baseX;
        float fractionY = sampleY - baseY;
        float fractionZ = sampleZ - baseZ;
        out[0] = 0.0f;
        out[1] = 0.0f;
        out[2] = 0.0f;
        for (int dy = 0; dy <= 1; dy++) {
            float weightY = dy == 0 ? 1.0f - fractionY : fractionY;
            if (weightY <= 0.0f) continue;
            for (int dz = 0; dz <= 1; dz++) {
                float weightZ = dz == 0 ? 1.0f - fractionZ : fractionZ;
                if (weightZ <= 0.0f) continue;
                for (int dx = 0; dx <= 1; dx++) {
                    float weightX = dx == 0 ? 1.0f - fractionX : fractionX;
                    float weight = weightX * weightY * weightZ;
                    if (weight <= 0.0f) continue;
                    int packed = light.packedAtCurrentDimension(
                            baseX + dx, baseY + dy, baseZ + dz
                    );
                    out[0] += weight * PackedRgbLight.red(packed) / 15.0f;
                    out[1] += weight * PackedRgbLight.green(packed) / 15.0f;
                    out[2] += weight * PackedRgbLight.blue(packed) / 15.0f;
                }
            }
        }
    }

    private static int toChannel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    private static int litChannel(int source, float illumination) {
        return Math.max(0, Math.min(255, Math.round(source * Math.min(1.0f, illumination))));
    }

    public static void updateSkyPalette(ClientLevel level) {
        double gamma = Minecraft.getInstance().options.gamma().get();
        float ambient = 0.04f + 0.16f * (float) Math.max(0.0, Math.min(1.0, gamma));
        float timeFactor = Math.max(0.0f, (15.0f - level.getSkyDarken()) / 15.0f);
        String dimension = level.dimension().identifier().toString();
        if ("minecraft:the_nether".equals(dimension)) {
            skyPalette = new SkyPalette(1.0f, 0.34f, 0.16f, timeFactor, ambient);
        } else if ("minecraft:the_end".equals(dimension)) {
            skyPalette = new SkyPalette(0.58f, 0.48f, 1.0f, timeFactor, ambient);
        } else {
            float daylight = daylight(level);
            float night = 1.0f - daylight;
            skyPalette = new SkyPalette(
                    0.28f * night + 1.00f * daylight,
                    0.38f * night + 0.93f * daylight,
                    0.82f * night + 0.78f * daylight,
                    timeFactor,
                    ambient
            );
        }
    }

    /** RGB terrain's unlit baseline at a sky level, shared with the moving-light composite. */
    static Vector3f skyAndAmbientAt(float skyLight) {
        SkyPalette palette = skyPalette;
        float skyWeight = Math.max(0, Math.min(15, skyLight)) / 15.0f * palette.timeFactor();
        return new Vector3f(
                palette.ambient() + palette.red() * skyWeight,
                palette.ambient() + palette.green() * skyWeight,
                palette.ambient() + palette.blue() * skyWeight
        );
    }

    /** Uses the same user-facing ambient-occlusion toggle as vanilla smooth lighting. */
    private static int smoothedPackedAt(
            BlockPos pos, Direction faceDirection, ClientGameplayLightField.LocalLookup light
    ) {
        int center = light.packedAtCurrentDimension(pos);
        int face = faceDirection == null ? 0 : light.packedAtCurrentDimension(
                pos.getX() + faceDirection.getStepX(),
                pos.getY() + faceDirection.getStepY(),
                pos.getZ() + faceDirection.getStepZ()
        );
        if (!Minecraft.getInstance().options.ambientOcclusion().get()) {
            return PackedRgbLight.componentMax(center, face);
        }

        int red = PackedRgbLight.red(center) * 3;
        int green = PackedRgbLight.green(center) * 3;
        int blue = PackedRgbLight.blue(center) * 3;
        int samples = 3;
        for (Direction direction : SAMPLE_DIRECTIONS) {
            int sample = light.packedAtCurrentDimension(
                    pos.getX() + direction.getStepX(),
                    pos.getY() + direction.getStepY(),
                    pos.getZ() + direction.getStepZ()
            );
            if (sample == 0) {
                continue;
            }
            red += PackedRgbLight.red(sample);
            green += PackedRgbLight.green(sample);
            blue += PackedRgbLight.blue(sample);
            samples++;
        }
        if (face != 0 && faceDirection != null) {
            red += PackedRgbLight.red(face);
            green += PackedRgbLight.green(face);
            blue += PackedRgbLight.blue(face);
            samples++;
        }
        return PackedRgbLight.pack(
                Math.round((float) red / samples),
                Math.round((float) green / samples),
                Math.round((float) blue / samples)
        );
    }

    /** A coarse sky epoch for the RGB terrain mesh; ordinary ticks do not remesh the world. */
    public static int skyRefreshKey(ClientLevel level) {
        if (!"minecraft:overworld".equals(level.dimension().identifier().toString())) {
            return 0;
        }
        int paletteStep = Math.round(daylight(level) * 12.0f);
        return (level.getSkyDarken() << 5) | paletteStep;
    }

    private static float daylight(ClientLevel level) {
        long time = Math.floorMod(level.getOverworldClockTime(), 24_000L);
        double sunAngle = (time - 6_000.0) * (Math.PI * 2.0 / 24_000.0);
        return (float) Math.max(0.0, Math.cos(sunAngle));
    }

    private record SkyPalette(float red, float green, float blue, float timeFactor, float ambient) {
    }

    private static int toChannel(int value, int maximum) {
        return maximum <= 0 ? 255 : Math.max(0, Math.min(255, Math.round(value * 255.0f / maximum)));
    }
}
