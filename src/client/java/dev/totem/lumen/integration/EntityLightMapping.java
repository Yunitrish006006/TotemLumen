package dev.totem.lumen.integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

/** Carries an extracted world-light volume through vanilla's deferred model submission. */
record EntityLightMapping(UvMapping sprite, EntityLightVolume volume, Vec3 camera) implements UvMapping {
    public float getU(float u) { return sprite == null ? u : sprite.getU(u); }
    public float getV(float v) { return sprite == null ? v : sprite.getV(v); }

    @Override
    public VertexConsumer wrap(VertexConsumer delegate) {
        return new LitVertices(sprite == null ? delegate : sprite.wrap(delegate));
    }

    private final class LitVertices implements VertexConsumer {
        private final VertexConsumer delegate;
        private int tint = -1;

        LitVertices(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        private int tint(float x, float y, float z) {
            return volume.tintAt(camera.x + x, camera.y + y, camera.z + z);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay,
                int light, float nx, float ny, float nz) {
            delegate.addVertex(x, y, z, ARGB.multiply(color, tint(x, y, z)), u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            tint = tint(x, y, z);
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(ARGB.color(a, r, g, b)); }
        @Override
        public VertexConsumer setColor(int color) { delegate.setColor(ARGB.multiply(color, tint)); return this; }
        @Override
        public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override
        public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override
        public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override
        public VertexConsumer setUv3(float u, float v) { delegate.setUv3(u, v); return this; }
        @Override
        public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
        @Override
        public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
    }
}
