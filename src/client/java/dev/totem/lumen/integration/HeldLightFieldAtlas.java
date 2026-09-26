package dev.totem.lumen.integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.totem.lumen.gameplay.light.PackedRgbLight;
import dev.totem.lumen.TotemLumenClient;

import java.util.Arrays;
import java.util.List;

/** Four bounded, source-relative 32³ windows of the same client RGB field used by terrain. */
final class HeldLightFieldAtlas {
    static final int SIDE = 32;
    static final int TILE_WIDTH = 256;
    static final int TILE_HEIGHT = 128;
    static final int WIDTH = 512;
    static final int HEIGHT = 256;
    private static final int SLOTS = 4;

    private static final int[] entityIds = new int[SLOTS];
    private static final int[] originsX = new int[SLOTS];
    private static final int[] originsY = new int[SLOTS];
    private static final int[] originsZ = new int[SLOTS];
    private static final long[] revisions = new long[SLOTS];
    private static final char[][][] sectionReferences = new char[SLOTS][][];
    private static String dimension;
    private static GpuDevice device;
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static NativeImage pixels;
    private static int diagnosticUpdates;

    static {
        Arrays.fill(revisions, Long.MIN_VALUE);
    }

    private HeldLightFieldAtlas() {
    }

    static GpuTextureView update(CommandEncoder encoder, List<ClientHeldLightState.Light> lights,
                                 String activeDimension) {
        long started = System.nanoTime();
        GpuDevice currentDevice = RenderSystem.getDevice();
        if (texture == null || texture.isClosed() || device != currentDevice) {
            if (view != null) view.close();
            if (texture != null) texture.close();
            texture = currentDevice.createTexture("Totem Lumen held-light RGB field",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
            view = currentDevice.createTextureView(texture);
            if (pixels == null) pixels = new NativeImage(WIDTH, HEIGHT, false);
            device = currentDevice;
            Arrays.fill(revisions, Long.MIN_VALUE);
            Arrays.fill(sectionReferences, null);
        }

        long revision = ClientGameplayLightField.revision();
        boolean dimensionChanged = !activeDimension.equals(dimension);
        boolean changed = false;
        int refreshedSlots = 0;
        for (int slot = 0; slot < Math.min(SLOTS, lights.size()); slot++) {
            ClientHeldLightState.Light light = lights.get(slot);
            int x = origin(light.x());
            int y = origin(light.y());
            int z = origin(light.z());
            boolean moved = dimensionChanged || entityIds[slot] != light.entityId()
                    || originsX[slot] != x || originsY[slot] != y || originsZ[slot] != z;
            if (moved || revisions[slot] != revision) {
                char[][] current = collectSections(x, y, z);
                boolean localChanged = !sameReferences(sectionReferences[slot], current);
                revisions[slot] = revision;
                if (!moved && !localChanged) continue;
                fill(slot, x, y, z, current);
                sectionReferences[slot] = current;
                entityIds[slot] = light.entityId();
                originsX[slot] = x;
                originsY[slot] = y;
                originsZ[slot] = z;
                changed = true;
                refreshedSlots++;
            }
        }
        dimension = activeDimension;
        if (changed) {
            encoder.writeToTexture(texture, pixels);
            double millis = (System.nanoTime() - started) / 1_000_000.0;
            if (diagnosticUpdates < 3 || (millis > 8.0 && diagnosticUpdates < 8)) {
                TotemLumenClient.LOGGER.info("Held RGB field atlas updated: slots={}, revision={}, cpuMs={}",
                        refreshedSlots, revision, String.format(java.util.Locale.ROOT, "%.2f", millis));
                diagnosticUpdates++;
            }
        }
        return view;
    }

    static int origin(float coordinate) {
        return (int) Math.floor(coordinate) - SIDE / 2;
    }

    static GpuTextureView view() { return view; }

    static int originX(int slot) { return originsX[slot]; }
    static int originY(int slot) { return originsY[slot]; }
    static int originZ(int slot) { return originsZ[slot]; }

    static int atlasX(int slot, int x, int z) {
        return (slot & 1) * TILE_WIDTH + (z & 7) * SIDE + x;
    }

    static int atlasY(int slot, int y, int z) {
        return (slot >>> 1) * TILE_HEIGHT + y * 4 + (z >>> 3);
    }

    private static char[][] collectSections(int originX, int originY, int originZ) {
        char[][] references = new char[27][];
        int startX = Math.floorDiv(originX, 16);
        int startY = Math.floorDiv(originY, 16);
        int startZ = Math.floorDiv(originZ, 16);
        int endX = Math.floorDiv(originX + SIDE - 1, 16);
        int endY = Math.floorDiv(originY + SIDE - 1, 16);
        int endZ = Math.floorDiv(originZ + SIDE - 1, 16);
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    if (startX + x <= endX && startY + y <= endY && startZ + z <= endZ) {
                        references[(y * 3 + z) * 3 + x] = ClientGameplayLightField.localSectionReference(
                                startX + x, startY + y, startZ + z);
                    }
                }
            }
        }
        return references;
    }

    static boolean sameReferences(char[][] previous, char[][] current) {
        if (previous == null) return false;
        for (int i = 0; i < current.length; i++) {
            if (previous[i] != current[i]) return false;
        }
        return true;
    }

    private static void fill(int slot, int originX, int originY, int originZ, char[][] sections) {
        int startX = Math.floorDiv(originX, 16);
        int startY = Math.floorDiv(originY, 16);
        int startZ = Math.floorDiv(originZ, 16);
        int[] sectionXByCell = new int[SIDE];
        for (int x = 0; x < SIDE; x++) {
            sectionXByCell[x] = Math.floorDiv(originX + x, 16) - startX;
        }
        for (int y = 0; y < SIDE; y++) {
            int sectionY = Math.floorDiv(originY + y, 16) - startY;
            for (int z = 0; z < SIDE; z++) {
                int sectionZ = Math.floorDiv(originZ + z, 16) - startZ;
                int atlasY = atlasY(slot, y, z);
                int sectionBase = (sectionY * 3 + sectionZ) * 3;
                int voxelBase = (((originY + y) & 15) << 8) | (((originZ + z) & 15) << 4);
                for (int x = 0; x < SIDE; x++) {
                    char[] values = sections[sectionBase + sectionXByCell[x]];
                    int packed = values == null ? 0 : values[voxelBase | ((originX + x) & 15)];
                    int red = PackedRgbLight.red(packed) * 17;
                    int green = PackedRgbLight.green(packed) * 17;
                    int blue = PackedRgbLight.blue(packed) * 17;
                    pixels.setPixelABGR(atlasX(slot, x, z), atlasY,
                            0xFF000000 | (blue << 16) | (green << 8) | red);
                }
            }
        }
    }
}
