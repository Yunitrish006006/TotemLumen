package dev.totem.lumen.integration;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import dev.totem.lumen.render.RendererSettings;
import dev.totem.lumen.TotemLumenClient;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;

/**
 * Depth-tested RGB presentation for the vanilla RGB profile.
 *
 * <p>This deliberately sits after vanilla terrain and draws only the coloured light volumes. It
 * does not touch chunk meshes, quad alpha, or compiled-section invalidation. The upload uses the
 * official command-encoder staging path; the heap ByteBuffer GpuDevice overload is intentionally
 * not used because it is unsafe on the Apple/MoltenVK backend.</p>
 */
public final class VanillaRgbOverlayRenderer {
    private static final int MAX_CELLS = 8192;
    private static final int MAX_FIELD_SCAN_DISTANCE = 64;
    private static final double MAX_FIELD_SCAN_DISTANCE_SQUARED =
            (double) MAX_FIELD_SCAN_DISTANCE * MAX_FIELD_SCAN_DISTANCE;
    private static final int VERTICES_PER_CUBE = 36;
    private static final int FLOATS_PER_VERTEX = 3;
    private static final int BYTES_PER_VERTEX = 16;
    private static final long UPLOAD_DEBOUNCE_NANOS = 150_000_000L;
    // World-space vertices are reused while the camera moves. Re-select the nearest presentation
    // budget only after the camera has moved two blocks, which avoids rescanning every RGB cell
    // during ordinary mouse/camera movement.
    private static final double CAMERA_RESELECT_DISTANCE_SQUARED = 4.0;
    private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("totem-lumen", "vanilla_rgb_overlay");

    private static final RenderPipeline PIPELINE = createPipeline();

    private static GpuBuffer vertexBuffer;
    private static int vertexCount;
    private static long uploadedRevision = Long.MIN_VALUE;
    private static long loggedRevision = Long.MIN_VALUE;
    private static long pendingRevision = Long.MIN_VALUE;
    private static long pendingSinceNanos = Long.MIN_VALUE;
    private static Vec3 lastSelectionCameraPosition;
    private static long uploadedContentHash = Long.MIN_VALUE;
    private static long lastReuseLogNanos = Long.MIN_VALUE;
    private static final List<RetiredBuffer> retired = new ArrayList<>();
    private static final BlockPos.MutableBlockPos SURFACE_POS = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();

    private VanillaRgbOverlayRenderer() {
    }

    private static RenderPipeline createPipeline() {
        RenderPipeline vanilla = RenderPipelines.DEBUG_FILLED_BOX;
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(PIPELINE_ID)
                .withVertexShader(vanilla.getVertexShader())
                .withFragmentShader(vanilla.getFragmentShader())
                .withColorTargetState(new ColorTargetState(
                        // RGB gameplay light is emissive contribution on top of vanilla's
                        // already-composited terrain, not a translucent replacement for the
                        // terrain. TRANSLUCENT made the daytime lightmap wash the hue toward
                        // white; additive composition preserves the colored contribution.
                        Optional.of(BlendFunction.ADDITIVE),
                        vanilla.getColorTargetState().format(),
                        ColorTargetState.WRITE_ALL
                ))
                // Reverse-Z depth with the same polygon-offset convention Minecraft uses for
                // its depth-biased world overlays. The bias moves boundary faces in front of
                // the already-rendered vanilla surface without disabling occlusion.
                .withDepthStencilState(new DepthStencilState(
                        CompareOp.GREATER_THAN_OR_EQUAL,
                        false,
                        0.0f,
                        0.0f
                ))
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        // Projection, dynamic transforms, and fog are separate bind groups in 26.2. Copy the
        // complete layout instead of assuming the first group contains every vanilla uniform.
        for (var layout : vanilla.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        return builder.build();
    }

    public static void render(Matrix4fc modelView) {
        // MINECRAFT_RGB uses RGB values directly in vanilla chunk quads. The old volume overlay
        // is intentionally disabled: drawing boundary faces here creates visible one-block
        // squares instead of a base RGB light stack.
        if (RendererSettings.renderProfile() != RendererSettings.RenderProfile.MINECRAFT_RGB
                || Minecraft.getInstance().level == null
                || !RenderSystem.isOnRenderThread()) {
            return;
        }

        reapRetired();
        String dimension = Minecraft.getInstance().level.dimension().identifier().toString();
        long revision = ClientGameplayLightField.revision();
        long now = System.nanoTime();
        if (revision != pendingRevision) {
            pendingRevision = revision;
            pendingSinceNanos = now;
        }
        boolean revisionStable = pendingSinceNanos != Long.MIN_VALUE
                && now - pendingSinceNanos >= UPLOAD_DEBOUNCE_NANOS;
        boolean immediateUpload = ClientGameplayLightField.consumeImmediateUploadRequest();
        Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position();
        boolean cameraMoved = lastSelectionCameraPosition == null
                || lastSelectionCameraPosition.distanceToSqr(cameraPosition) >= CAMERA_RESELECT_DISTANCE_SQUARED;
        if ((revision != uploadedRevision && (immediateUpload || revisionStable))
                || (revision == uploadedRevision && cameraMoved)) {
            rebuild(dimension, revision, cameraPosition);
        }
        if (vertexBuffer == null || vertexCount == 0) {
            return;
        }

        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTextureView() == null || target.getDepthTextureView() == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Totem Lumen vanilla RGB overlay",
                target.getColorTextureView(),
                Optional.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();
            pass.setUniform(
                    "DynamicTransforms",
                    uniforms.writeTransform(
                            // GameRenderer supplies a view-rotation matrix. Apply the camera
                            // translation here so the GPU buffer can remain in world space and
                            // does not follow the player or require a full re-upload per move.
                            new Matrix4f(modelView).translate(
                                    (float) -cameraPosition.x,
                                    (float) -cameraPosition.y,
                                    (float) -cameraPosition.z
                            ),
                            new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                            new Vector3f(),
                            new Matrix4f()
                    )
            );
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.draw(vertexCount, 1, 0, 0);
        }
        encoder.submit();
    }

    private static void rebuild(String dimension, long revision, Vec3 cameraPosition) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        List<ClientGameplayLightField.GpuSection> sections = ClientGameplayLightField.snapshotForGpu(dimension);
        // Allocate against the bounded presentation budget, not section count. A section can
        // contain 4096 cells, but allocating that worst case for every section would turn a
        // sparse update into a multi-gigabyte native allocation.
        ByteBuffer data = MemoryUtil.memAlloc(Math.max(1, MAX_CELLS * VERTICES_PER_CUBE * BYTES_PER_VERTEX))
                .order(ByteOrder.nativeOrder());
        try {
            Map<SectionKey, char[]> sectionValues = new HashMap<>();
            for (ClientGameplayLightField.GpuSection section : sections) {
                sectionValues.put(new SectionKey(section.x(), section.y(), section.z()), section.values());
            }
            PriorityQueue<BoundaryCell> nearest = new PriorityQueue<>(
                    MAX_CELLS,
                    (left, right) -> Double.compare(right.distanceSquared(), left.distanceSquared())
            );
            for (ClientGameplayLightField.GpuSection section : sections) {
                if (outsideScanRange(section, cameraPosition)) {
                    continue;
                }
                char[] values = section.values();
                for (int index = 0; index < values.length; index++) {
                    int packed = values[index];
                    int localX = index & 15;
                    int localZ = (index >>> 4) & 15;
                    int localY = (index >>> 8) & 15;
                    int x = section.x() * 16 + localX;
                    int y = section.y() * 16 + localY;
                    int z = section.z() * 16 + localZ;
                    LIGHT_POS.set(x, y, z);
                    int vanillaLight = level.getBrightness(LightLayer.BLOCK, LIGHT_POS);
                    if (vanillaLight <= 0) {
                        // Vanilla lighting is the immediate removal gate. A stale RGB section
                        // must never keep a coloured volume alive after its source is removed.
                        continue;
                    }
                    if (packed == 0) {
                        BlockState state = level.getBlockState(LIGHT_POS);
                        if (state.getLightEmission() <= 0) {
                            continue;
                        }
                        packed = sourcePacked(state);
                    }
                    int faceMask = surfaceFaceMask(level, x, y, z);
                    if (faceMask == 0) {
                        continue;
                    }
                    double dx = x + 0.5 - cameraPosition.x;
                    double dy = y + 0.5 - cameraPosition.y;
                    double dz = z + 0.5 - cameraPosition.z;
                    BoundaryCell candidate = new BoundaryCell(
                            x, y, z, packed, faceMask, dx * dx + dy * dy + dz * dz
                    );
                    if (nearest.size() < MAX_CELLS) {
                        nearest.add(candidate);
                    } else if (candidate.distanceSquared() < nearest.peek().distanceSquared()) {
                        nearest.poll();
                        nearest.add(candidate);
                    }
                }
            }
            List<BoundaryCell> selected = new ArrayList<>(nearest);
            selected.sort(java.util.Comparator.comparingDouble(BoundaryCell::distanceSquared));
            List<BoundaryCell> hashCells = new ArrayList<>(selected);
            hashCells.sort(java.util.Comparator
                    .comparingInt(BoundaryCell::x)
                    .thenComparingInt(BoundaryCell::y)
                    .thenComparingInt(BoundaryCell::z));
            HashSet<Integer> selectedColors = new HashSet<>();
            int channelMask = 0;
            long contentHash = 0xcbf29ce484222325L;
            for (BoundaryCell cell : hashCells) {
                int packed = cell.packed();
                contentHash = mixHash(contentHash, cell.x());
                contentHash = mixHash(contentHash, cell.y());
                contentHash = mixHash(contentHash, cell.z());
                contentHash = mixHash(contentHash, packed);
                contentHash = mixHash(contentHash, cell.faceMask());
            }
            for (BoundaryCell cell : selected) {
                int packed = smoothPacked(sectionValues, cell.x(), cell.y(), cell.z(), cell.packed());
                selectedColors.add(packed);
                if (dev.totem.lumen.gameplay.light.PackedRgbLight.red(packed) != 0) channelMask |= 1;
                if (dev.totem.lumen.gameplay.light.PackedRgbLight.green(packed) != 0) channelMask |= 2;
                if (dev.totem.lumen.gameplay.light.PackedRgbLight.blue(packed) != 0) channelMask |= 4;
                putCube(
                        data,
                        cell.x(), cell.y(), cell.z(),
                        packed, cell.faceMask()
                );
            }
            lastSelectionCameraPosition = cameraPosition;
            if (contentHash == uploadedContentHash) {
                data.clear();
                uploadedRevision = revision;
                long now = System.nanoTime();
                if (lastReuseLogNanos == Long.MIN_VALUE || now - lastReuseLogNanos >= 2_000_000_000L) {
                    lastReuseLogNanos = now;
                    TotemLumenClient.LOGGER.info(
                            "Vanilla RGB overlay reuse: revision={}, litCells={}, vertexCount={}, contentHash={}",
                            revision,
                            selected.size(),
                            vertexCount,
                            Long.toUnsignedString(contentHash, 16)
                    );
                }
                return;
            }
            data.flip();
            int bytes = data.remaining();
            if (bytes == 0) {
                retireCurrent();
                vertexCount = 0;
                uploadedRevision = revision;
                uploadedContentHash = contentHash;
                logRevision(revision, 0, 0, 0);
                return;
            }

            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(
                    () -> "Totem Lumen vanilla RGB vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    bytes
            );
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(replacement.slice(0, bytes), data);
            GpuFence fence = vertexBuffer == null ? null : encoder.createFence();
            if (vertexBuffer != null) {
                retired.add(new RetiredBuffer(vertexBuffer, fence));
            }
            encoder.submit();
            vertexBuffer = replacement;
            vertexCount = bytes / BYTES_PER_VERTEX;
            uploadedRevision = revision;
            uploadedContentHash = contentHash;
            logRevision(revision, selected.size(), selectedColors.size(), channelMask);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** Skips remote synchronized sections that cannot contribute to the nearby RGB presentation. */
    private static boolean outsideScanRange(
            ClientGameplayLightField.GpuSection section,
            Vec3 cameraPosition
    ) {
        double minX = section.x() * 16.0;
        double minY = section.y() * 16.0;
        double minZ = section.z() * 16.0;
        double maxX = minX + 16.0;
        double maxY = minY + 16.0;
        double maxZ = minZ + 16.0;
        double dx = cameraPosition.x < minX ? minX - cameraPosition.x
                : cameraPosition.x > maxX ? cameraPosition.x - maxX : 0.0;
        double dy = cameraPosition.y < minY ? minY - cameraPosition.y
                : cameraPosition.y > maxY ? cameraPosition.y - maxY : 0.0;
        double dz = cameraPosition.z < minZ ? minZ - cameraPosition.z
                : cameraPosition.z > maxZ ? cameraPosition.z - maxZ : 0.0;
        return dx * dx + dy * dy + dz * dz > MAX_FIELD_SCAN_DISTANCE_SQUARED;
    }

    /**
     * Only present RGB air light on a real neighbouring block surface. Open-air field boundaries
     * are useful for debugging but are not lighting, and rendering them was the source of the
     * large visible voxel wall in the client.
     */
    private static int surfaceFaceMask(
            ClientLevel level,
            int x,
            int y,
            int z
    ) {
        if (!level.isInsideBuildHeight(y) || !level.hasChunkAt(x, z)) {
            return 0;
        }
        SURFACE_POS.set(x, y, z);
        BlockState current = level.getBlockState(SURFACE_POS);
        if (!current.isAir() && !current.getCollisionShape(level, SURFACE_POS).isEmpty()) {
            return 0;
        }
        int mask = 0;
        if (isSolidSurface(level, x, y - 1, z)) mask |= 1;
        if (isSolidSurface(level, x - 1, y, z)) mask |= 2;
        if (isSolidSurface(level, x + 1, y, z)) mask |= 4;
        if (isSolidSurface(level, x, y, z + 1)) mask |= 8;
        if (isSolidSurface(level, x, y, z - 1)) mask |= 16;
        if (isSolidSurface(level, x, y + 1, z)) mask |= 32;
        return mask;
    }

    private static boolean isSolidSurface(ClientLevel level, int x, int y, int z) {
        if (!level.isInsideBuildHeight(y) || !level.hasChunkAt(x, z)) {
            return false;
        }
        SURFACE_POS.set(x, y, z);
        BlockState state = level.getBlockState(SURFACE_POS);
        return !state.isAir() && !state.getCollisionShape(level, SURFACE_POS).isEmpty();
    }

    /**
     * Blends a cell with its lit neighbours before presenting it as an air-volume face. The
     * authoritative field stays quantized; only the presentation is softened so the RGB light
     * does not read as a stack of opaque one-block cubes.
     */
    private static int smoothPacked(
            Map<SectionKey, char[]> sections,
            int x,
            int y,
            int z,
            int packed
    ) {
        int red = dev.totem.lumen.gameplay.light.PackedRgbLight.red(packed) * 3;
        int green = dev.totem.lumen.gameplay.light.PackedRgbLight.green(packed) * 3;
        int blue = dev.totem.lumen.gameplay.light.PackedRgbLight.blue(packed) * 3;
        int samples = 3;
        int[][] directions = {
                {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        for (int[] direction : directions) {
            int neighbour = packedAt(sections, x + direction[0], y + direction[1], z + direction[2]);
            if (neighbour == 0) {
                continue;
            }
            red += dev.totem.lumen.gameplay.light.PackedRgbLight.red(neighbour);
            green += dev.totem.lumen.gameplay.light.PackedRgbLight.green(neighbour);
            blue += dev.totem.lumen.gameplay.light.PackedRgbLight.blue(neighbour);
            samples++;
        }
        return dev.totem.lumen.gameplay.light.PackedRgbLight.pack(
                Math.round((float) red / samples),
                Math.round((float) green / samples),
                Math.round((float) blue / samples)
        );
    }

    private static int sourcePacked(BlockState state) {
        String sourceId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString();
        dev.totem.lumen.world.LightingWorldRule rule = ClientLightingWorldRules.ruleFor(sourceId);
        dev.totem.lumen.gameplay.light.EmissionColor color = rule == null
                ? dev.totem.lumen.gameplay.light.DefaultEmissionColors.forBlock(sourceId, state.getLightEmission())
                : new dev.totem.lumen.gameplay.light.EmissionColor(
                        rule.emissionR(), rule.emissionG(), rule.emissionB()
                );
        return dev.totem.lumen.gameplay.light.PackedRgbLight.fromNormalized(
                color, rule == null ? state.getLightEmission() : rule.gameplayStrengthOr(state.getLightEmission())
        );
    }

    private static int packedAt(Map<SectionKey, char[]> sections, int x, int y, int z) {
        int sectionX = Math.floorDiv(x, 16);
        int sectionY = Math.floorDiv(y, 16);
        int sectionZ = Math.floorDiv(z, 16);
        char[] values = sections.get(new SectionKey(sectionX, sectionY, sectionZ));
        if (values == null) {
            return 0;
        }
        return values[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    private static void putCube(
            ByteBuffer out,
            int x,
            int y,
            int z,
            int packed,
            int faceMask
    ) {
        int maximum = dev.totem.lumen.gameplay.light.PackedRgbLight.alpha(packed);
        float intensity = maximum / 15.0f;
        // Keep propagated intensity for alpha, but normalize the chroma so attenuation does not
        // turn every distant colored light into a nearly white translucent overlay.
        float r = dev.totem.lumen.gameplay.light.PackedRgbLight.hueRed(packed) / 15.0f;
        float g = dev.totem.lumen.gameplay.light.PackedRgbLight.hueGreen(packed) / 15.0f;
        float b = dev.totem.lumen.gameplay.light.PackedRgbLight.hueBlue(packed) / 15.0f;
        // Add a bounded emissive contribution. The square-root response keeps attenuated light
        // visible without making every propagated cell equally bright. Alpha is opaque because
        // the additive pipeline uses ONE factors; it is not a transparency control here.
        // Lower-energy cells are deliberately much softer. They are numerous in the air field,
        // so the previous square-root response made every attenuation step look like a hard cube.
        float contribution = 0.04f + 0.30f * intensity;
        int color = 0xFF000000
                | ((int) Math.min(255.0f, b * contribution * 255.0f) << 16)
                | ((int) Math.min(255.0f, g * contribution * 255.0f) << 8)
                | (int) Math.min(255.0f, r * contribution * 255.0f);
        // Move each boundary face into the lit cell, not into the unlit neighbour. This places
        // a face just in front of a blocking vanilla surface from the light volume's side; the
        // old outward offset required a huge polygon bias and produced a wall-penetrating grid.
        // Keep faces just inside the lit air cell. Expanding into the neighbouring solid block
        // makes reverse-Z depth testing hide the RGB contribution entirely.
        float x0 = x + 0.002f, y0 = y + 0.002f, z0 = z + 0.002f;
        float x1 = x + 0.998f, y1 = y + 0.998f, z1 = z + 0.998f;
        if ((faceMask & 1) != 0) face(out, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color);
        if ((faceMask & 2) != 0) face(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color);
        if ((faceMask & 4) != 0) face(out, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color);
        if ((faceMask & 8) != 0) face(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color);
        if ((faceMask & 16) != 0) face(out, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color);
        if ((faceMask & 32) != 0) face(out, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, color);
    }

    private static void face(ByteBuffer out, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int color) {
        vertex(out, ax, ay, az, color); vertex(out, bx, by, bz, color); vertex(out, cx, cy, cz, color);
        vertex(out, ax, ay, az, color); vertex(out, cx, cy, cz, color); vertex(out, dx, dy, dz, color);
    }

    private static void vertex(ByteBuffer out, float x, float y, float z, int color) {
        out.putFloat(x).putFloat(y).putFloat(z);
        // POSITION_COLOR uses GpuFormat.RGBA8_UNORM. Write the four channels explicitly instead
        // of relying on ByteBuffer/native-endian behavior on a particular Vulkan backend.
        out.put((byte) (color & 0xFF));
        out.put((byte) ((color >>> 8) & 0xFF));
        out.put((byte) ((color >>> 16) & 0xFF));
        out.put((byte) ((color >>> 24) & 0xFF));
    }

    private static long mixHash(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value);
        return hash * 0x100000001b3L;
    }

    private static void reapRetired() {
        retired.removeIf(entry -> {
            if (!entry.fence.awaitCompletion(0)) return false;
            entry.fence.close();
            entry.buffer.close();
            return true;
        });
    }

    private static void closeCurrent() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    private static void retireCurrent() {
        if (vertexBuffer == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuFence fence = encoder.createFence();
        retired.add(new RetiredBuffer(vertexBuffer, fence));
        encoder.submit();
        vertexBuffer = null;
    }

    private static void logRevision(long revision, int cells, int distinctColors, int channelMask) {
        if (revision == loggedRevision) {
            return;
        }
        loggedRevision = revision;
        TotemLumenClient.LOGGER.info(
                "Vanilla RGB overlay upload: revision={}, litCells={}, vertexCount={}, distinctColors={}, channels={}, maxCells={}",
                revision,
                cells,
                vertexCount,
                distinctColors,
                channelMask == 0 ? "none" : ((channelMask & 1) != 0 ? "R" : "")
                        + ((channelMask & 2) != 0 ? "G" : "")
                        + ((channelMask & 4) != 0 ? "B" : ""),
                MAX_CELLS
        );
    }

    private record RetiredBuffer(GpuBuffer buffer, com.mojang.blaze3d.buffers.GpuFence fence) {
    }

    private record BoundaryCell(int x, int y, int z, int packed, int faceMask, double distanceSquared) {
    }

    private record SectionKey(int x, int y, int z) {
    }
}
