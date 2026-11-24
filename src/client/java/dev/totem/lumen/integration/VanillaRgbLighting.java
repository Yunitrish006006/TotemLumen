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
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.util.Mth;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import java.util.concurrent.atomic.AtomicInteger;

/** Resolves the client-local RGB field for Minecraft's terrain geometry. */
public final class VanillaRgbLighting {
    private static final AtomicInteger DIAGNOSTIC_LOGS = new AtomicInteger();
    private static final AtomicInteger INDIGO_DIAGNOSTIC_LOGS = new AtomicInteger();
    private static final ThreadLocal<float[]> RGB_SAMPLE = ThreadLocal.withInitial(() -> new float[3]);
    /** Immutable per-tick snapshot read by parallel terrain-mesh workers. */
    private static volatile SkyPalette skyPalette = new SkyPalette(0.92f, 0.96f, 1.0f, 1.0f, 0.04f, 1.0f, null);
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
        boolean fire = ClientRgbVisualLightSource.isFire(state);
        if (fire) packed = PackedRgbLight.componentMax(packed, ClientRgbVisualLightSource.packedFor(state));
        boolean emissive = state.emissiveRendering() || state.getLightEmission() > 0;
        int skyLight = emissive ? fallbackSky(world, pos, faceDirection) : 0;
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
        } else if (!fire && state.getLightEmission() > 0) {
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

        blockGreen = RgbSurfaceLightMath.torchGreen(blockRed, blockGreen, blockBlue);

        // Sky light and block light share one terrain-light budget. A sunlit surface should
        // not become brighter just because a torch is placed beside it.
        SkyPalette palette = skyPalette;
        float blockGain = ClientLightingWorldRules.tuning().brightnessMultiplier();
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
        boolean skyLit = false;
        for (int vertex = 0; vertex < 4; vertex++) {
            float vertexSky = RgbSkyLightMath.vertexSkyLevel(
                    instance.getLightCoords(vertex), skyLight, emissive
            );
            skyLit |= vertexSky > 0.0f;
            red = palette.illumination(0, vertexSky, blockRed, blockGain);
            green = palette.illumination(1, vertexSky, blockGreen, blockGain);
            blue = palette.illumination(2, vertexSky, blockBlue, blockGain);
            int source = instance.getColor(vertex);
            int tintedRed = litChannel(ARGB.red(source), red);
            int tintedGreen = litChannel(ARGB.green(source), green);
            int tintedBlue = litChannel(ARGB.blue(source), blue);
            instance.setColor(vertex, ARGB.color(
                    ARGB.alpha(source), tintedRed, tintedGreen, tintedBlue
            ));
            // A full-bright lookup is neutral here: all spatial block and sky brightness is
            // already in the RGB vertex channels, so the vanilla scalar lightmap cannot add
            // white block light a second time.
            instance.setLightCoords(vertex, rgbLightmap);
        }
        if (skyLit) {
            ClientGameplayLightField.noteSkyLitSurface(pos);
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
        boolean emissive = quad.emissive() || state.emissiveRendering() || state.getLightEmission() > 0;
        int skyLight = emissive ? fallbackSky(world, pos, faceDirection) : 0;
        float fallbackRed = 0.0f;
        float fallbackGreen = 0.0f;
        float fallbackBlue = 0.0f;
        boolean fire = ClientRgbVisualLightSource.isFire(state);
        if (fire) {
            int source = ClientRgbVisualLightSource.packedFor(state);
            fallbackRed = PackedRgbLight.red(source) / 15.0f;
            fallbackGreen = PackedRgbLight.green(source) / 15.0f;
            fallbackBlue = PackedRgbLight.blue(source) / 15.0f;
        } else if (state.getLightEmission() > 0) {
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
        float blockGain = ClientLightingWorldRules.tuning().brightnessMultiplier();
        if (INDIGO_DIAGNOSTIC_LOGS.get() < 4 && INDIGO_DIAGNOSTIC_LOGS.getAndIncrement() < 4) {
            dev.totem.lumen.TotemLumenClient.LOGGER.info(
                    "RGB Indigo terrain active: pos={}, center=0x{}, vertexSampling={}",
                    pos, Integer.toHexString(light.packedAtCurrentDimension(pos)),
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
        boolean skyLit = false;
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
            if (fire) {
                blockRed = Math.max(blockRed, fallbackRed);
                blockGreen = Math.max(blockGreen, fallbackGreen);
                blockBlue = Math.max(blockBlue, fallbackBlue);
            } else if (blockRed == 0.0f && blockGreen == 0.0f && blockBlue == 0.0f
                    && state.getLightEmission() > 0) {
                blockRed = fallbackRed;
                blockGreen = fallbackGreen;
                blockBlue = fallbackBlue;
            }
            blockGreen = RgbSurfaceLightMath.torchGreen(blockRed, blockGreen, blockBlue);
            float vertexSky = RgbSkyLightMath.vertexSkyLevel(
                    quad.lightmap(vertex), skyLight, emissive
            );
            skyLit |= vertexSky > 0.0f;
            int source = quad.color(vertex);
            quad.color(vertex, ARGB.color(
                    ARGB.alpha(source),
                    litChannel(ARGB.red(source), palette.illumination(0, vertexSky, blockRed, blockGain)),
                    litChannel(ARGB.green(source), palette.illumination(1, vertexSky, blockGreen, blockGain)),
                    litChannel(ARGB.blue(source), palette.illumination(2, vertexSky, blockBlue, blockGain))
            ));
            quad.lightmap(vertex, LightCoordsUtil.FULL_BRIGHT);
        }
        if (skyLit) {
            ClientGameplayLightField.noteSkyLitSurface(pos);
        }
    }

    /** Samples RGB light at a surface vertex, interpolating effective channels without repacking. */
    private static void sampleRgbAt(
            double worldX, double worldY, double worldZ, Direction face,
            ClientGameplayLightField.LocalLookup light, float[] out
    ) {
        float offsetX = face == null ? 0.0f : face.getStepX() * 0.5f;
        float offsetY = face == null ? 0.0f : face.getStepY() * 0.5f;
        float offsetZ = face == null ? 0.0f : face.getStepZ() * 0.5f;
        double sampleX = worldX + offsetX - 0.5f;
        double sampleY = worldY + offsetY - 0.5f;
        double sampleZ = worldZ + offsetZ - 0.5f;
        int baseX = (int) Math.floor(sampleX);
        int baseY = (int) Math.floor(sampleY);
        int baseZ = (int) Math.floor(sampleZ);
        float fractionX = (float) (sampleX - baseX);
        float fractionY = (float) (sampleY - baseY);
        float fractionZ = (float) (sampleZ - baseZ);
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

    static int modelTint(double x, double y, double z, int skyLevel, int emissionFloor) {
        float[] rgb = RGB_SAMPLE.get();
        sampleRgbAt(x, y, z, null, ClientGameplayLightField.localSampler(), rgb);
        rgb[1] = RgbSurfaceLightMath.torchGreen(rgb[0], rgb[1], rgb[2]);
        SkyPalette palette = skyPalette;
        float gain = ClientLightingWorldRules.tuning().brightnessMultiplier();
        float floor = emissionFloor / 15.0f;
        return ARGB.color(255,
                litChannel(255, Math.max(floor, palette.illumination(0, skyLevel, rgb[0], gain))),
                litChannel(255, Math.max(floor, palette.illumination(1, skyLevel, rgb[1], gain))),
                litChannel(255, Math.max(floor, palette.illumination(2, skyLevel, rgb[2], gain))));
    }

    public static void updateSkyPalette(ClientLevel level) {
        double gamma = Minecraft.getInstance().options.gamma().get();
        float ambient = 0.04f + 0.16f * (float) Math.max(0.0, Math.min(1.0, gamma));
        float timeFactor = Math.max(0.0f, (15.0f - level.getSkyDarken()) / 15.0f);
        String dimension = level.dimension().identifier().toString();
        if ("minecraft:the_nether".equals(dimension)) {
            skyPalette = new SkyPalette(1.0f, 0.34f, 0.16f, timeFactor, ambient + 0.04f, 0.0f, null);
        } else if ("minecraft:the_end".equals(dimension)) {
            skyPalette = new SkyPalette(0.58f, 0.48f, 1.0f, timeFactor, ambient, 0.0f, null);
        } else {
            float daylight = daylight(level);
            float night = 1.0f - daylight;
            DaylightMap map = null;
            if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                float blend = DaylightMap.dayWeight(level.getOverworldClockTime());
                if (blend > 0) {
                    var probe = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe();
                    map = new DaylightMap(blend, probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f),
                            probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, 1f),
                            probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 1f), (float) gamma);
                }
            }
            skyPalette = new SkyPalette(
                    0.28f * night + daylight,
                    0.38f * night + daylight,
                    0.82f * night + daylight,
                    timeFactor,
                    ambient,
                    daylight, map
            );
        }
    }

    // Ordinary quads already carry smoothed sky coordinates from Minecraft.
    // Emissive geometry needs a world sample because its lightmap may be full-bright.
    private static int fallbackSky(BlockAndTintGetter world, BlockPos pos, Direction face) {
        int center = world.getBrightness(LightLayer.SKY, pos);
        return center == 15 || face == null ? center
                : Math.max(center, world.getBrightness(LightLayer.SKY, pos.relative(face)));
    }

    /** A coarse sky epoch for the RGB terrain mesh; ordinary ticks do not remesh the world. */
    public static int skyRefreshKey(ClientLevel level) {
        if (!"minecraft:overworld".equals(level.dimension().identifier().toString())) {
            return 0;
        }
        SkyPalette palette = skyPalette;
        if (palette.map != null && palette.map.weight == 1f) return palette.map.refreshKey;
        int key = (level.getSkyDarken() << 5) | Math.round(palette.daylight * 12.0f);
        return palette.map == null ? key : 31 * key + palette.map.refreshKey;
    }

    private static float daylight(ClientLevel level) {
        long time = Math.floorMod(level.getOverworldClockTime(), 24_000L);
        double sunAngle = (time - 6_000.0) * (Math.PI * 2.0 / 24_000.0);
        return (float) Math.max(0.0, Math.cos(sunAngle));
    }

    private record SkyPalette(float red, float green, float blue, float timeFactor,
                              float ambient, float daylight, DaylightMap map) {
        float illumination(int channel, float level, float block, float gain) {
            float sky = level * timeFactor / 15f;
            float tint = channel == 0 ? red : channel == 1 ? green : blue;
            if (map == null || level <= 0) {
                return RgbSurfaceLightMath.illumination(ambient, block, tint * sky, sky * daylight, gain);
            }
            float coverage = Mth.lerp(map.weight, sky * daylight, level / 15f * map.coverage);
            float artificial = RgbSurfaceLightMath.illumination(ambient, block, 0, coverage, gain);
            float sunlight = Mth.lerp(map.weight, ambient + tint * sky, map.at(channel, level));
            return Math.max(artificial, sunlight);
        }
    }

    private static int toChannel(int value, int maximum) {
        return maximum <= 0 ? 255 : Math.max(0, Math.min(255, Math.round(value * 255.0f / maximum)));
    }
}
