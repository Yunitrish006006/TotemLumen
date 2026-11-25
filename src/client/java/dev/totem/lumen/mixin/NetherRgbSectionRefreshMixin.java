package dev.totem.lumen.mixin;

import dev.totem.lumen.integration.ClientGameplayLightField;
import dev.totem.lumen.integration.NetherRgbSectionRefresh;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class NetherRgbSectionRefreshMixin implements NetherRgbSectionRefresh {
    @Shadow public abstract long getSectionNode();
    @Shadow public abstract SectionMesh getSectionMesh();
    @Unique private String totemLumen$pendingDimension;
    @Unique private long totemLumen$pendingNode;

    // Lighting updates during the first terrain build would cancel it. Coalesce
    // them until upload so the next mesh can sample the latest RGB field.
    @Override
    public synchronized boolean totemLumen$deferRgbRefresh(String dimension, long node) {
        if (node != getSectionNode() || getSectionMesh() != CompiledSectionMesh.UNCOMPILED) return false;
        totemLumen$pendingNode = node;
        totemLumen$pendingDimension = dimension;
        return true;
    }

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private synchronized void totemLumen$afterFirstMesh(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> ci) {
        if (totemLumen$pendingDimension == null || mesh == CompiledSectionMesh.UNCOMPILED) return;
        long node = totemLumen$pendingNode;
        if (getSectionNode() == node && mesh != CompiledSectionMesh.EMPTY) {
            ClientGameplayLightField.markSectionDirty(totemLumen$pendingDimension,
                    SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        }
        totemLumen$pendingDimension = null;
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private synchronized void totemLumen$forgetPreviousSection(CallbackInfo ci) {
        totemLumen$pendingDimension = null;
    }
}
