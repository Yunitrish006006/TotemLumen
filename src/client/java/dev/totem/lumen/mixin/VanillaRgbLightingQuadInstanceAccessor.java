package dev.totem.lumen.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer")
public interface VanillaRgbLightingQuadInstanceAccessor {
    @Accessor("quadInstance")
    QuadInstance totemLumen$getQuadInstance();
}
