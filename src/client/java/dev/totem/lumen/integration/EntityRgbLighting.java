package dev.totem.lumen.integration;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.world.entity.animal.squid.GlowSquid;

/** Material-independent RGB illumination captured from the terrain field at the vanilla entity probe. */
public record EntityRgbLighting(int lightCoords, int tint, EntityLightVolume volume, Vec3 camera) {
    public interface State {
        EntityRgbLighting totemLumen$getLighting();
        void totemLumen$setLighting(EntityRgbLighting lighting);
    }

    private static final ThreadLocal<EntityRgbLighting> CURRENT = new ThreadLocal<>();

    public static EntityRgbLighting sample(LivingEntity entity, float partialTick, int coords) {
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB
                || entity.level() != Minecraft.getInstance().level || entity.isOnFire()) return null;
        if (entity instanceof Allay || entity instanceof Blaze || entity instanceof Vex
                || entity instanceof WitherBoss || entity instanceof MagmaCube) return null;
        int floor = entity instanceof GlowSquid squid
                ? (int) Mth.clampedLerp(1.0f - squid.getDarkTicksRemaining() / 10.0f, 0.0f, 15.0f) : 0;
        if (floor == 15) return null;
        var pos = entity.getLightProbePosition(partialTick);
        int tint = VanillaRgbLighting.modelTint(pos.x, pos.y, pos.z, LightCoordsUtil.sky(coords), floor);
        return new EntityRgbLighting(coords, tint, EntityLightVolume.capture(entity, partialTick, floor), null);
    }

    public EntityRgbLighting forWorld(Vec3 origin) {
        return volume == null ? this : new EntityRgbLighting(lightCoords, tint, volume, origin);
    }

    public UvMapping spatialMapping(UvMapping sprite) {
        return new EntityLightMapping(sprite, volume, camera);
    }

    public static EntityRgbLighting current() {
        return CURRENT.get();
    }

    public static void select(EntityRgbLighting lighting) {
        if (lighting == null) CURRENT.remove();
        else CURRENT.set(lighting);
    }

    public boolean applies(RenderType type, int submittedLight) {
        var pipeline = type.pipeline();
        return submittedLight == lightCoords && !type.isOutline()
                && RenderPipelines.ENTITY_SOLID.getShaders().get(ShaderType.VERTEX)
                    .equals(pipeline.getShaders().get(ShaderType.VERTEX))
                && !pipeline.getShaderDefines().flags().contains("EMISSIVE");
    }
}
