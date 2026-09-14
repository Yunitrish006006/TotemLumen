package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.gpu.GpuSectionLookupTable;
import dev.totem.lumen.gpu.GpuSectionSlotAllocator;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.SectionKey;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent P5/P6 renderer using stable section slots plus an open-addressed GPU hash lookup.
 *
 * <p>The shader no longer linearly scans every resident section for each DDA voxel. CPU scene
 * changes update a 128-bucket section-coordinate lookup and fixed 16 KiB voxel slots. Camera-only
 * frames still upload only the small header. P6 reuses the same primary-ray path and launches a
 * second DDA ray from the primary hit point toward a fixed directional debug light to validate
 * hard-shadow visibility without introducing soft-shadow sampling, culling, or GI yet.</p>
 */
public final class P5StableLookupRenderer {
    private static final int MAX_SECTIONS = 64;
    private static final int MIN_SECTIONS = 16;
    private static final int HEADER_WORDS = 64;
    private static final int LOOKUP_CAPACITY = 128;
    private static final int LOOKUP_BASE_WORD = HEADER_WORDS;
    private static final int LOOKUP_WORDS = LOOKUP_CAPACITY * GpuSectionLookupTable.WORDS_PER_BUCKET;
    private static final int VOXEL_BASE_WORD = LOOKUP_BASE_WORD + LOOKUP_WORDS;
    private static final int TARGET_WIDTH = 160;
    private static final int MAX_STEPS = 512;
    private static final float MAX_DISTANCE = 256.0f;
    private static final int CAMERA_UPLOAD_WORDS = 23;

    private static final GpuSectionSlotAllocator SLOT_ALLOCATOR = new GpuSectionSlotAllocator(MAX_SECTIONS);
    private static final Set<SectionKey> RESIDENT_KEYS = new HashSet<>();

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                uint data[];
            } scene;

            const uint LOOKUP_BASE = 64u;
            const uint LOOKUP_CAPACITY = 128u;
            const uint LOOKUP_MASK = 127u;
            const uint LOOKUP_WORDS_PER_BUCKET = 4u;
            const uint VOXEL_BASE = 576u;
            const uint VOXELS_PER_SECTION = 4096u;
            const vec3 DEBUG_LIGHT_DIRECTION = normalize(vec3(0.45, 0.85, 0.30));

            struct HitResult {
                uint hit;
                uint materialId;
                ivec3 voxel;
                ivec3 normal;
                float distance;
                uint steps;
            };

            int floorDiv16(int value) {
                return value >= 0 ? value / 16 : -((-value + 15) / 16);
            }

            uint sectionHash(ivec3 coord) {
                uint h = uint(coord.x) * 0x9E3779B1u;
                h ^= uint(coord.y) * 0x85EBCA77u;
                h ^= uint(coord.z) * 0xC2B2AE3Du;
                h ^= h >> 16u;
                return h;
            }

            int findSectionSlot(ivec3 sectionCoord) {
                uint start = sectionHash(sectionCoord) & LOOKUP_MASK;
                for (uint probe = 0u; probe < LOOKUP_CAPACITY; probe++) {
                    uint bucket = (start + probe) & LOOKUP_MASK;
                    uint base = LOOKUP_BASE + bucket * LOOKUP_WORDS_PER_BUCKET;
                    uint slotPlusOne = scene.data[base + 3u];
                    if (slotPlusOne == 0u) return -1;
                    ivec3 candidate = ivec3(
                        int(scene.data[base]),
                        int(scene.data[base + 1u]),
                        int(scene.data[base + 2u])
                    );
                    if (all(equal(candidate, sectionCoord))) {
                        return int(slotPlusOne - 1u);
                    }
                }
                return -1;
            }

            uint materialAt(ivec3 voxel) {
                ivec3 sectionCoord = ivec3(
                    floorDiv16(voxel.x),
                    floorDiv16(voxel.y),
                    floorDiv16(voxel.z)
                );
                int slot = findSectionSlot(sectionCoord);
                if (slot < 0) return 0u;

                ivec3 local = voxel - sectionCoord * 16;
                uint index = uint((local.y << 8) | (local.z << 4) | local.x);
                uint voxelBase = VOXEL_BASE + uint(slot) * VOXELS_PER_SECTION;
                return scene.data[voxelBase + index];
            }

            HitResult traceRay(vec3 origin, vec3 direction) {
                HitResult result;
                result.hit = 0u;
                result.materialId = 0u;
                result.voxel = ivec3(0);
                result.normal = ivec3(0);
                result.distance = 0.0;
                result.steps = 0u;

                float directionLength = length(direction);
                if (directionLength < 0.000001) return result;

                vec3 dir = direction / directionLength;
                ivec3 voxel = ivec3(floor(origin));
                ivec3 step = ivec3(
                    dir.x > 0.0 ? 1 : (dir.x < 0.0 ? -1 : 0),
                    dir.y > 0.0 ? 1 : (dir.y < 0.0 ? -1 : 0),
                    dir.z > 0.0 ? 1 : (dir.z < 0.0 ? -1 : 0)
                );
                const float INF = 1.0e30;
                vec3 tDelta = vec3(
                    step.x == 0 ? INF : abs(1.0 / dir.x),
                    step.y == 0 ? INF : abs(1.0 / dir.y),
                    step.z == 0 ? INF : abs(1.0 / dir.z)
                );
                vec3 tMax = vec3(
                    step.x > 0 ? (float(voxel.x + 1) - origin.x) / dir.x : (step.x < 0 ? (float(voxel.x) - origin.x) / dir.x : INF),
                    step.y > 0 ? (float(voxel.y + 1) - origin.y) / dir.y : (step.y < 0 ? (float(voxel.y) - origin.y) / dir.y : INF),
                    step.z > 0 ? (float(voxel.z + 1) - origin.z) / dir.z : (step.z < 0 ? (float(voxel.z) - origin.z) / dir.z : INF)
                );

                ivec3 normal = ivec3(0);
                float distance = 0.0;
                uint maxSteps = scene.data[6];
                float maxDistance = uintBitsToFloat(scene.data[7]);

                for (uint iteration = 0u; iteration < maxSteps; iteration++) {
                    uint materialId = materialAt(voxel);
                    if (materialId != 0u) {
                        result.hit = 1u;
                        result.materialId = materialId;
                        result.voxel = voxel;
                        result.normal = normal;
                        result.distance = distance;
                        result.steps = iteration;
                        return result;
                    }

                    if (tMax.x <= tMax.y && tMax.x <= tMax.z) {
                        distance = tMax.x;
                        if (distance > maxDistance) break;
                        voxel.x += step.x;
                        normal = ivec3(-step.x, 0, 0);
                        tMax.x += tDelta.x;
                    } else if (tMax.y <= tMax.z) {
                        distance = tMax.y;
                        if (distance > maxDistance) break;
                        voxel.y += step.y;
                        normal = ivec3(0, -step.y, 0);
                        tMax.y += tDelta.y;
                    } else {
                        distance = tMax.z;
                        if (distance > maxDistance) break;
                        voxel.z += step.z;
                        normal = ivec3(0, 0, -step.z);
                        tMax.z += tDelta.z;
                    }
                }

                result.voxel = voxel;
                result.normal = normal;
                result.distance = distance;
                result.steps = maxSteps;
                return result;
            }

            uint packRgba(vec3 rgb, uint alpha) {
                uvec3 c = uvec3(clamp(rgb, vec3(0.0), vec3(1.0)) * 255.0 + 0.5);
                return c.r | (c.g << 8u) | (c.b << 16u) | ((alpha & 255u) << 24u);
            }

            vec3 materialColor(uint materialId) {
                uint h = materialId * 1664525u + 1013904223u;
                return vec3(
                    float((h >> 0u) & 255u),
                    float((h >> 8u) & 255u),
                    float((h >> 16u) & 255u)
                ) / 255.0;
            }

            uint debugColor(HitResult hit, vec3 primaryOrigin, vec3 primaryDirection) {
                if (hit.hit == 0u) return packRgba(vec3(0.03, 0.05, 0.08), 255u);

                uint mode = scene.data[22];
                if (mode == 0u) {
                    return packRgba(vec3(hit.normal) * 0.5 + 0.5, 255u);
                }
                if (mode == 1u) {
                    return packRgba(materialColor(hit.materialId), 255u);
                }
                if (mode == 2u) {
                    float maxDistance = uintBitsToFloat(scene.data[7]);
                    float v = 1.0 - clamp(hit.distance / maxDistance, 0.0, 1.0);
                    return packRgba(vec3(v), 255u);
                }
                if (mode == 3u) {
                    float t = clamp(float(hit.steps) / max(float(scene.data[6]), 1.0), 0.0, 1.0);
                    return packRgba(vec3(t, t * t, 1.0 - t), 255u);
                }

                vec3 surfaceNormal = normalize(vec3(hit.normal));
                float nDotL = max(dot(surfaceNormal, DEBUG_LIGHT_DIRECTION), 0.0);
                float visibility = 0.0;
                if (nDotL > 0.0) {
                    vec3 hitPoint = primaryOrigin + primaryDirection * hit.distance;
                    vec3 shadowOrigin = hitPoint + surfaceNormal * 0.02 + DEBUG_LIGHT_DIRECTION * 0.01;
                    HitResult blocker = traceRay(shadowOrigin, DEBUG_LIGHT_DIRECTION);
                    visibility = blocker.hit == 0u ? 1.0 : 0.0;
                }

                float lighting = 0.12 + 0.88 * nDotL * visibility;
                return packRgba(materialColor(hit.materialId) * lighting, 255u);
            }

            void main() {
                uvec2 pixel = gl_GlobalInvocationID.xy;
                uint width = scene.data[4];
                uint height = scene.data[5];
                if (pixel.x >= width || pixel.y >= height) return;

                vec3 origin = vec3(
                    uintBitsToFloat(scene.data[8]),
                    uintBitsToFloat(scene.data[9]),
                    uintBitsToFloat(scene.data[10])
                );
                vec3 right = vec3(
                    uintBitsToFloat(scene.data[11]),
                    uintBitsToFloat(scene.data[12]),
                    uintBitsToFloat(scene.data[13])
                );
                vec3 up = vec3(
                    uintBitsToFloat(scene.data[14]),
                    uintBitsToFloat(scene.data[15]),
                    uintBitsToFloat(scene.data[16])
                );
                vec3 forward = vec3(
                    uintBitsToFloat(scene.data[17]),
                    uintBitsToFloat(scene.data[18]),
                    uintBitsToFloat(scene.data[19])
                );
                float tanHalfFov = uintBitsToFloat(scene.data[20]);
                float aspect = uintBitsToFloat(scene.data[21]);

                float ndcX = ((float(pixel.x) + 0.5) / float(width)) * 2.0 - 1.0;
                float ndcY = 1.0 - ((float(pixel.y) + 0.5) / float(height)) * 2.0;
                vec3 direction = normalize(
                    forward + right * (ndcX * aspect * tanHalfFov) + up * (ndcY * tanHalfFov)
                );

                HitResult primaryHit = traceRay(origin, direction);
                uint pixelBase = scene.data[3];
                scene.data[pixelBase + pixel.y * width + pixel.x] = debugColor(primaryHit, origin, direction);
            }
            """;

    public enum DebugMode {
        NORMAL(0, "Normal"),
        MATERIAL(1, "Material"),
        DISTANCE(2, "Distance"),
        STEPS(3, "Steps"),
        HARD_SHADOW(4, "Hard Shadow");

        private final int shaderValue;
        private final String label;

        DebugMode(int shaderValue, String label) {
            this.shaderValue = shaderValue;
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static Resources resources;
    private static DebugMode mode = DebugMode.HARD_SHADOW;
    private static boolean inFlight;
    private static boolean ready;
    private static boolean resetRequested;
    private static boolean firstFrameLogged;
    private static boolean sceneUploadRequired = true;
    private static long sceneSignature = Long.MIN_VALUE;
    private static long lastSubmittedFrame = -1;
    private static int lastLookupMaxProbe;
    private static String dimensionId;

    private P5StableLookupRenderer() {
    }

    public static void runOnRenderThread() {
        if (!P5WorldDebugComposite.ready() || inFlight || resetRequested) return;

        FrameSnapshot frame = SceneExtractionBridge.latestFrame();
        if (frame == null || !frame.dimensionId().equals(SceneExtractionBridge.scene().activeDimension())) return;
        if (frame.frameIndex() == lastSubmittedFrame) return;
        if (SceneExtractionBridge.scene().populatedSectionCount() < MIN_SECTIONS) return;

        VulkanCapabilities capabilities = dev.totem.lumen.render.RendererBootstrap.vulkanCapabilities();
        VulkanDevice device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) return;

        List<SectionSnapshot> sections = nearestSections(frame);
        if (sections.size() < MIN_SECTIONS) return;

        int windowWidth = Math.max(1, Minecraft.getInstance().getWindow().getWidth());
        int windowHeight = Math.max(1, Minecraft.getInstance().getWindow().getHeight());
        int targetHeight = Math.max(1, Math.round(TARGET_WIDTH * (windowHeight / (float) windowWidth)));

        if (resources == null || resources.height != targetHeight) {
            destroyResourcesIfSafe();
            resources = Resources.create(device, TARGET_WIDTH, targetHeight);
            sceneUploadRequired = true;
            sceneSignature = Long.MIN_VALUE;
            ready = false;
        }

        if (!frame.dimensionId().equals(dimensionId)) {
            resetSceneSlots();
            dimensionId = frame.dimensionId();
            sceneUploadRequired = true;
            sceneSignature = Long.MIN_VALUE;
            ready = false;
        }

        long newSignature = sectionSignature(sections);
        if (newSignature != sceneSignature) {
            syncStableSlots(sections);
            sceneSignature = newSignature;
            sceneUploadRequired = true;
        }

        Resources r = resources;
        ByteBuffer upload = r.upload.mappedView();
        packHeader(upload, frame, r.width, r.height, sections.size(), mode);

        int uploadBytes;
        if (sceneUploadRequired) {
            packLookupAndSections(upload, sections);
            uploadBytes = r.pixelBaseWord * Integer.BYTES;
        } else {
            uploadBytes = CAMERA_UPLOAD_WORDS * Integer.BYTES;
        }
        r.upload.flush(0, uploadBytes);

        boolean fullSceneUpload = sceneUploadRequired;
        sceneUploadRequired = false;
        inFlight = true;
        lastSubmittedFrame = frame.frameIndex();

        try {
            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, r.commandPool);
            recordCommands(batch.commandBuffer(), r, uploadBytes);
            batch.finishAndEnqueue(() -> onFrameComplete(fullSceneUpload));
        } catch (Throwable failure) {
            inFlight = false;
            sceneUploadRequired |= fullSceneUpload;
            TotemLumenClient.LOGGER.error("P6 hard-shadow frame submission failed", failure);
        }
    }

    public static void tickLifecycle(Minecraft client) {
        if (client.level == null) {
            ready = false;
            if (resources != null) {
                if (inFlight) {
                    resetRequested = true;
                } else {
                    destroyResourcesIfSafe();
                }
            }
        }
    }

    public static DebugMode cycleMode() {
        DebugMode[] values = DebugMode.values();
        mode = values[(mode.ordinal() + 1) % values.length];
        return mode;
    }

    public static DebugMode mode() {
        return mode;
    }

    public static void drawHud(GuiGraphicsExtractor graphics) {
        Resources r = resources;
        if (!ready || r == null || r.view.isClosed()) {
            P5WorldDebugComposite.drawHud(graphics);
            return;
        }

        graphics.blit(
                r.view,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                0,
                0,
                graphics.guiWidth(),
                graphics.guiHeight(),
                0.0f,
                1.0f,
                0.0f,
                1.0f
        );
    }

    public static void shutdown() {
        ready = false;
        if (inFlight) {
            resetRequested = true;
        } else {
            destroyResourcesIfSafe();
        }
    }

    private static void onFrameComplete(boolean fullSceneUpload) {
        inFlight = false;
        ready = true;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            TotemLumenClient.LOGGER.info(
                    "P5 stable GPU lookup READY: {}x{}, slots={}/{}, lookupMaxProbe={}, mode={}, sceneUpload={}",
                    resources == null ? 0 : resources.width,
                    resources == null ? 0 : resources.height,
                    SLOT_ALLOCATOR.usedSlots(), SLOT_ALLOCATOR.capacity(), lastLookupMaxProbe,
                    mode.label(), fullSceneUpload
            );
            TotemLumenClient.LOGGER.info(
                    "P6 hard shadow debug READY: secondary DDA visibility ray active toward fixed directional test light"
            );
        }
        if (resetRequested) {
            resetRequested = false;
            destroyResourcesIfSafe();
        }
    }

    private static List<SectionSnapshot> nearestSections(FrameSnapshot frame) {
        List<SectionSnapshot> snapshots = new ArrayList<>(SceneExtractionBridge.scene().sectionSnapshots());
        snapshots.removeIf(snapshot -> !snapshot.key().dimensionId().equals(frame.dimensionId()));
        snapshots.sort(Comparator.comparingDouble(snapshot -> distanceSquared(frame, snapshot)));
        if (snapshots.size() > MAX_SECTIONS) {
            snapshots = new ArrayList<>(snapshots.subList(0, MAX_SECTIONS));
        }
        return List.copyOf(snapshots);
    }

    private static double distanceSquared(FrameSnapshot frame, SectionSnapshot snapshot) {
        double x = snapshot.key().x() * 16.0 + 8.0 - frame.cameraX();
        double y = snapshot.key().y() * 16.0 + 8.0 - frame.cameraY();
        double z = snapshot.key().z() * 16.0 + 8.0 - frame.cameraZ();
        return x * x + y * y + z * z;
    }

    private static long sectionSignature(List<SectionSnapshot> sections) {
        long hash = 0xcbf29ce484222325L;
        for (SectionSnapshot snapshot : sections) {
            hash ^= snapshot.key().x();
            hash *= 0x100000001b3L;
            hash ^= snapshot.key().y();
            hash *= 0x100000001b3L;
            hash ^= snapshot.key().z();
            hash *= 0x100000001b3L;
            hash ^= snapshot.revision();
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void syncStableSlots(List<SectionSnapshot> sections) {
        Set<SectionKey> selected = new HashSet<>();
        for (SectionSnapshot snapshot : sections) selected.add(snapshot.key());

        for (SectionKey resident : List.copyOf(RESIDENT_KEYS)) {
            if (!selected.contains(resident)) {
                SLOT_ALLOCATOR.remove(resident);
                RESIDENT_KEYS.remove(resident);
            }
        }
        for (SectionSnapshot snapshot : sections) {
            SLOT_ALLOCATOR.apply(snapshot);
            RESIDENT_KEYS.add(snapshot.key());
        }
    }

    private static void resetSceneSlots() {
        SLOT_ALLOCATOR.clear();
        RESIDENT_KEYS.clear();
        lastLookupMaxProbe = 0;
    }

    private static void packHeader(
            ByteBuffer buffer,
            FrameSnapshot frame,
            int width,
            int height,
            int sectionCount,
            DebugMode debugMode
    ) {
        putWord(buffer, 1, sectionCount);
        putWord(buffer, 2, LOOKUP_CAPACITY);
        putWord(buffer, 3, Resources.pixelBaseWord());
        putWord(buffer, 4, width);
        putWord(buffer, 5, height);
        putWord(buffer, 6, MAX_STEPS);
        putWord(buffer, 7, Float.floatToRawIntBits(MAX_DISTANCE));
        putWord(buffer, 8, Float.floatToRawIntBits((float) frame.cameraX()));
        putWord(buffer, 9, Float.floatToRawIntBits((float) frame.cameraY()));
        putWord(buffer, 10, Float.floatToRawIntBits((float) frame.cameraZ()));

        float[][] basis = cameraBasis(frame);
        putVec3(buffer, 11, basis[0]);
        putVec3(buffer, 14, basis[1]);
        putVec3(buffer, 17, basis[2]);
        putWord(buffer, 20, Float.floatToRawIntBits((float) Math.tan(Math.toRadians(frame.fovDegrees()) * 0.5)));
        putWord(buffer, 21, Float.floatToRawIntBits(width / (float) height));
        putWord(buffer, 22, debugMode.shaderValue);
    }

    private static void packLookupAndSections(ByteBuffer buffer, List<SectionSnapshot> sections) {
        GpuSectionLookupTable lookup = new GpuSectionLookupTable(LOOKUP_CAPACITY);
        for (SectionSnapshot snapshot : sections) {
            int slot = SLOT_ALLOCATOR.slotFor(snapshot.key());
            if (slot < 0) {
                throw new IllegalStateException("Missing stable GPU slot for " + snapshot.key());
            }
            lookup.put(snapshot.key().x(), snapshot.key().y(), snapshot.key().z(), slot);
            int voxelOffset = VOXEL_BASE_WORD + slot * SectionVoxelData.VOXEL_COUNT;
            int[] materialIds = snapshot.voxels().copyMaterialIds();
            for (int voxel = 0; voxel < materialIds.length; voxel++) {
                putWord(buffer, voxelOffset + voxel, materialIds[voxel]);
            }
        }
        lookup.writeTo(buffer, LOOKUP_BASE_WORD);
        lastLookupMaxProbe = lookup.maxProbe();
    }

    private static float[][] cameraBasis(FrameSnapshot frame) {
        return new float[][]{
                rotate(frame, 1.0f, 0.0f, 0.0f),
                rotate(frame, 0.0f, 1.0f, 0.0f),
                rotate(frame, 0.0f, 0.0f, -1.0f)
        };
    }

    private static float[] rotate(FrameSnapshot frame, float vx, float vy, float vz) {
        float qx = frame.rotationX();
        float qy = frame.rotationY();
        float qz = frame.rotationZ();
        float qw = frame.rotationW();
        float tx = 2.0f * (qy * vz - qz * vy);
        float ty = 2.0f * (qz * vx - qx * vz);
        float tz = 2.0f * (qx * vy - qy * vx);
        float rx = vx + qw * tx + (qy * tz - qz * ty);
        float ry = vy + qw * ty + (qz * tx - qx * tz);
        float rz = vz + qw * tz + (qx * ty - qy * tx);
        float length = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (length < 0.000001f) return new float[]{vx, vy, vz};
        return new float[]{rx / length, ry / length, rz / length};
    }

    private static void putVec3(ByteBuffer buffer, int base, float[] value) {
        putWord(buffer, base, Float.floatToRawIntBits(value[0]));
        putWord(buffer, base + 1, Float.floatToRawIntBits(value[1]));
        putWord(buffer, base + 2, Float.floatToRawIntBits(value[2]));
    }

    private static void putWord(ByteBuffer buffer, int wordIndex, int value) {
        buffer.putInt(wordIndex * Integer.BYTES, value);
    }

    private static void recordCommands(VkCommandBuffer commandBuffer, Resources r, int uploadBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer uploadCopy = VkBufferCopy.calloc(1, stack);
            uploadCopy.get(0).srcOffset(0).dstOffset(0).size(uploadBytes);
            VK10.vkCmdCopyBuffer(commandBuffer, r.upload.vkBuffer(), r.scene.vkBuffer(), uploadCopy);

            VkBufferMemoryBarrier.Buffer uploadToCompute = VkBufferMemoryBarrier.calloc(1, stack);
            uploadToCompute.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(r.scene.vkBuffer())
                    .offset(0)
                    .size(r.totalBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, uploadToCompute, null
            );

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, r.program.pipeline());
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    r.program.pipelineLayout(),
                    0,
                    stack.longs(r.program.descriptorSet()),
                    null
            );
            VK10.vkCmdDispatch(commandBuffer, (r.width + 7) / 8, (r.height + 7) / 8, 1);

            long pixelOffset = (long) r.pixelBaseWord * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(r.scene.vkBuffer())
                    .offset(pixelOffset)
                    .size(r.pixelBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, computeToCopy, null
            );

            VkImageMemoryBarrier.Buffer imageForCopy = VkImageMemoryBarrier.calloc(1, stack);
            imageForCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(ready ? VK10.VK_ACCESS_SHADER_READ_BIT : 0)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(r.vkImage);
            imageForCopy.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    ready ? VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, imageForCopy
            );

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).bufferOffset(pixelOffset).bufferRowLength(r.width).bufferImageHeight(r.height);
            copy.get(0).imageSubresource()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.get(0).imageOffset().set(0, 0, 0);
            copy.get(0).imageExtent().set(r.width, r.height, 1);
            VK10.vkCmdCopyBufferToImage(
                    commandBuffer, r.scene.vkBuffer(), r.vkImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, copy
            );

            VkImageMemoryBarrier.Buffer imageReady = VkImageMemoryBarrier.calloc(1, stack);
            imageReady.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(r.vkImage);
            imageReady.get(0).subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, null, imageReady
            );
        }
    }

    private static void destroyResourcesIfSafe() {
        if (resources == null || inFlight) return;
        Resources old = resources;
        resources = null;
        closeQuietly(old.view);
        closeQuietly(old.texture);
        closeQuietly(old.program);
        closeQuietly(old.upload);
        closeQuietly(old.scene);
        closeQuietly(old.commandPool);
        resetSceneSlots();
        ready = false;
        sceneUploadRequired = true;
        sceneSignature = Long.MIN_VALUE;
        lastSubmittedFrame = -1;
        dimensionId = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P6 hard-shadow resource", exception);
        }
    }

    private static final class Resources {
        final int width;
        final int height;
        final int pixelBaseWord;
        final int pixelBytes;
        final int totalBytes;
        final VulkanOwnedBuffer upload;
        final VulkanOwnedBuffer scene;
        final VulkanComputeProgram program;
        final VulkanFrameCommandPool commandPool;
        final GpuTexture texture;
        final GpuTextureView view;
        final long vkImage;

        private Resources(
                int width, int height, int pixelBaseWord, int pixelBytes, int totalBytes,
                VulkanOwnedBuffer upload, VulkanOwnedBuffer scene, VulkanComputeProgram program,
                VulkanFrameCommandPool commandPool, GpuTexture texture, GpuTextureView view, long vkImage
        ) {
            this.width = width;
            this.height = height;
            this.pixelBaseWord = pixelBaseWord;
            this.pixelBytes = pixelBytes;
            this.totalBytes = totalBytes;
            this.upload = upload;
            this.scene = scene;
            this.program = program;
            this.commandPool = commandPool;
            this.texture = texture;
            this.view = view;
            this.vkImage = vkImage;
        }

        static int pixelBaseWord() {
            return VOXEL_BASE_WORD + MAX_SECTIONS * SectionVoxelData.VOXEL_COUNT;
        }

        static Resources create(VulkanDevice device, int width, int height) {
            int pixelBaseWord = pixelBaseWord();
            int pixelBytes = Math.multiplyExact(Math.multiplyExact(width, height), Integer.BYTES);
            int totalWords = Math.addExact(pixelBaseWord, width * height);
            int totalBytes = Math.multiplyExact(totalWords, Integer.BYTES);

            VulkanOwnedBuffer upload = null;
            VulkanOwnedBuffer scene = null;
            VulkanComputeProgram program = null;
            VulkanFrameCommandPool commandPool = null;
            GpuTexture texture = null;
            GpuTextureView view = null;
            try {
                upload = VulkanOwnedBuffer.createUpload(device, totalBytes);
                scene = VulkanOwnedBuffer.createStorage(device, totalBytes);
                program = VulkanComputeProgram.create(device, "totem_lumen_p6_hard_shadow.comp", SHADER, scene);
                commandPool = new VulkanFrameCommandPool(device);
                texture = RenderSystem.getDevice().createTexture(
                        "Totem Lumen P6 hard shadow target",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM,
                        width, height, 1, 1
                );
                view = RenderSystem.getDevice().createTextureView(texture);
                if (!(texture instanceof VulkanGpuTexture vulkanTexture)) {
                    throw new IllegalStateException("P6 hard-shadow target is not backed by VulkanGpuTexture");
                }
                return new Resources(
                        width, height, pixelBaseWord, pixelBytes, totalBytes,
                        upload, scene, program, commandPool, texture, view, vulkanTexture.vkImage()
                );
            } catch (Throwable failure) {
                closeQuietly(view);
                closeQuietly(texture);
                closeQuietly(program);
                closeQuietly(upload);
                closeQuietly(scene);
                closeQuietly(commandPool);
                throw failure;
            }
        }
    }
}
