package dev.totem.lumen.integration;

import dev.totem.lumen.render.RendererSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

public interface NetherRgbSectionRefresh {
    boolean totemLumen$deferRgbRefresh(String dimension, long sectionNode);

    static void refresh(Minecraft client, ClientGameplayLightField.SectionCoordinate section) {
        if (client.level.dimension() == Level.NETHER
                && RendererSettings.renderProfile() == RendererSettings.RenderProfile.MINECRAFT_RGB) {
            var view = client.levelRenderer.viewArea();
            if (view != null) {
                var terrain = view.getRenderSectionAt(new BlockPos(section.x() << 4, section.y() << 4, section.z() << 4));
                if (terrain != null && terrain.getSectionMesh() == CompiledSectionMesh.UNCOMPILED
                        && ((NetherRgbSectionRefresh) terrain).totemLumen$deferRgbRefresh(section.dimension(),
                                SectionPos.asLong(section.x(), section.y(), section.z()))) return;
            }
        }
        client.level.setSectionRangeDirty(section.x(), section.y(), section.z(), section.x(), section.y(), section.z());
    }
}
