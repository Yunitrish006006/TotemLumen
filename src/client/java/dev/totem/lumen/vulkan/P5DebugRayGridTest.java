package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * P5A correctness gate: render a low-resolution full-frame DDA debug image entirely in Vulkan
 * compute, then read it back once for validation/statistics. Production rendering will keep the
 * debug target on GPU and composite it without per-frame CPU readback.
 */
public final class P5DebugRayGridTest {
    private static final int MAX_SECTIONS = 64;
    private static final int MIN_SECTIONS = 16;
    private static final int HEADER_WORDS = 64;
    private static final int SECTION_META_WORDS = 4;
    private static final int TARGET_WIDTH = 160;
    private static final int MAX_STEPS = 512;
    private static final float MAX_DISTANCE = 256.0f;
    private static final int MODE_NORMAL = 0;

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                uint data[];
            } scene;

            const uint SECTION_TABLE_BASE = 64u;
            const uint SECTION_META_WORDS = 4u;

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

            uint materialAt(ivec3 voxel) {
                ivec3 sectionCoord = ivec3(
                    floorDiv16(voxel.x),
                    floorDiv16(voxel.y),
                    floorDiv16(voxel.z)
                );
                ivec3 local = voxel - sectionCoord * 16;
                uint sectionCount = scene.data[1];

                for (uint i = 0u; i < sectionCount; i++) {
                    uint meta = SECTION_TABLE_BASE + i * SECTION_META_WORDS;
                    ivec3 candidate = ivec3(
                        int(scene.data[meta]),
                        int(scene.data[meta + 1u]),
                        int(scene.data[meta + 2u])
                    );
                    if (all(equal(candidate, sectionCoord))) {
                        uint voxelBase = scene.data[meta + 3u];
                        uint index = uint((local.y << 8) | (local.z << 4) | local.x);
                        return scene.data[voxelBase + index];
                    }
                }
                return 0u;
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
                if (directionLength < 0.000001) {
                    return result;
                }

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

            uint debugColor(HitResult hit) {
                if (hit.hit == 0u) {
                    return packRgba(vec3(0.03, 0.05, 0.08), 64u);
                }

                uint mode = scene.data[22];
                if (mode == 0u) {
                    vec3 n = vec3(hit.normal) * 0.5 + 0.5;
                    return packRgba(n, 255u);
                }
                if (mode == 1u) {
                    return packRgba(materialColor(hit.materialId), 255u);
                }
                if (mode == 2u) {
                    float maxDistance = uintBitsToFloat(scene.data[7]);
                    float v = 1.0 - clamp(hit.distance / maxDistance, 0.0, 1.0);
                    return packRgba(vec3(v), 255u);
                }

                float t = clamp(float(hit.steps) / max(float(scene.data[6]), 1.0), 0.0, 1.0);
                return packRgba(vec3(t, t * t, 1.0 - t), 255u);
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

                HitResult hit = traceRay(origin, direction);
                uint pixelBase = scene.data[3];
                scene.data[pixelBase + pixel.y * width + pixel.x] = debugColor(hit);
            }
            """;

    private static boolean attempted;
    private static volatile boolean passed;

    private P5DebugRayGridTest() {
    }

    public static void runOnceOnRenderThread() {
        if (attempted || !P4DdaSmokeTest.passed()) {
            return;
        }

        FrameSnapshot frame = SceneExtractionBridge.latestFrame();
        if (frame == null || !frame.dimensionId().equals(SceneExtractionBridge.scene().activeDimension())) {
            return;
        }
        if (SceneExtractionBridge.scene().populatedSectionCount() < MIN_SECTIONS) {
            return;
        }

        List<SectionSnapshot> sections = nearestSections(frame);
        if (sections.isEmpty()) {
            return;
        }

        VulkanCapabilities capabilities = dev.totem.lumen.render.RendererBootstrap.vulkanCapabilities();
        var device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            return;
        }

        int windowWidth = Math.max(1, Minecraft.getInstance().getWindow().getWidth());
        int windowHeight = Math.max(1, Minecraft.getInstance().getWindow().getHeight());
        int targetHeight = Math.max(1, Math.round(TARGET_WIDTH * (windowHeight / (float) windowWidth)));

        attempted = true;
        submit(frame, sections, TARGET_WIDTH, targetHeight, device, capabilities);
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

    private static void submit(
            FrameSnapshot frame,
            List<SectionSnapshot> sections,
            int width,
            int height,
            com.mojang.renderpearl.backend.vulkan.VulkanDevice device,
            VulkanCapabilities capabilities
    ) {
        int sectionCount = sections.size();
        int voxelBaseWord = HEADER_WORDS + sectionCount * SECTION_META_WORDS;
        int pixelBaseWord = voxelBaseWord + sectionCount * SectionVoxelData.VOXEL_COUNT;
        int pixelCount = Math.multiplyExact(width, height);
        int totalWords = Math.addExact(pixelBaseWord, pixelCount);
        int totalBytes = Math.multiplyExact(totalWords, Integer.BYTES);
        int pixelBytes = Math.multiplyExact(pixelCount, Integer.BYTES);

        VulkanFrameCommandPool commandPool = null;
        VulkanOwnedBuffer upload = null;
        VulkanOwnedBuffer scene = null;
        VulkanOwnedBuffer readback = null;
        VulkanComputeProgram program = null;
        try {
            commandPool = new VulkanFrameCommandPool(device);
            upload = VulkanOwnedBuffer.createUpload(device, totalBytes);
            scene = VulkanOwnedBuffer.createStorage(device, totalBytes);
            readback = VulkanOwnedBuffer.createReadback(device, pixelBytes);

            packScene(upload.mappedView(), frame, sections, width, height, voxelBaseWord, pixelBaseWord, totalWords);
            upload.flush(0, totalBytes);
            program = VulkanComputeProgram.create(device, "totem_lumen_p5_debug_grid.comp", SHADER, scene);

            VulkanFrameCommandPool finalCommandPool = commandPool;
            VulkanOwnedBuffer finalUpload = upload;
            VulkanOwnedBuffer finalScene = scene;
            VulkanOwnedBuffer finalReadback = readback;
            VulkanComputeProgram finalProgram = program;

            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, commandPool);
            recordCommands(
                    batch.commandBuffer(), program,
                    upload.vkBuffer(), scene.vkBuffer(), readback.vkBuffer(),
                    totalBytes, pixelBaseWord, pixelBytes, width, height
            );
            batch.finishAndEnqueue(() -> finishValidation(
                    finalReadback, width, height,
                    finalProgram, finalUpload, finalScene, finalCommandPool
            ));

            TotemLumenClient.LOGGER.info(
                    "P5 debug ray-grid submitted: {}x{}, sections={}, scene={} KiB, mode=normal",
                    width, height, sectionCount, totalBytes / 1024
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P5 debug ray-grid setup failed", failure);
            closeQuietly(program);
            closeQuietly(upload);
            closeQuietly(scene);
            closeQuietly(readback);
            closeQuietly(commandPool);
        }
    }

    private static void packScene(
            ByteBuffer buffer,
            FrameSnapshot frame,
            List<SectionSnapshot> sections,
            int width,
            int height,
            int voxelBaseWord,
            int pixelBaseWord,
            int totalWords
    ) {
        for (int word = 0; word < totalWords; word++) {
            putWord(buffer, word, 0);
        }

        putWord(buffer, 1, sections.size());
        putWord(buffer, 2, voxelBaseWord);
        putWord(buffer, 3, pixelBaseWord);
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

        float tanHalfFov = (float) Math.tan(Math.toRadians(frame.fovDegrees()) * 0.5);
        float aspect = width / (float) height;
        putWord(buffer, 20, Float.floatToRawIntBits(tanHalfFov));
        putWord(buffer, 21, Float.floatToRawIntBits(aspect));
        putWord(buffer, 22, MODE_NORMAL);

        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            SectionSnapshot snapshot = sections.get(sectionIndex);
            int meta = HEADER_WORDS + sectionIndex * SECTION_META_WORDS;
            int voxelOffset = voxelBaseWord + sectionIndex * SectionVoxelData.VOXEL_COUNT;
            putWord(buffer, meta, snapshot.key().x());
            putWord(buffer, meta + 1, snapshot.key().y());
            putWord(buffer, meta + 2, snapshot.key().z());
            putWord(buffer, meta + 3, voxelOffset);

            int[] materialIds = snapshot.voxels().copyMaterialIds();
            for (int voxel = 0; voxel < materialIds.length; voxel++) {
                putWord(buffer, voxelOffset + voxel, materialIds[voxel]);
            }
        }
    }

    /** Returns right, up, forward vectors obtained by rotating the camera's local basis. */
    private static float[][] cameraBasis(FrameSnapshot frame) {
        float[] right = rotate(frame, 1.0f, 0.0f, 0.0f);
        float[] up = rotate(frame, 0.0f, 1.0f, 0.0f);
        float[] forward = rotate(frame, 0.0f, 0.0f, -1.0f);
        return new float[][]{right, up, forward};
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
        if (length < 0.000001f) {
            return new float[]{vx, vy, vz};
        }
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

    private static void recordCommands(
            VkCommandBuffer commandBuffer,
            VulkanComputeProgram program,
            long uploadBuffer,
            long sceneBuffer,
            long readbackBuffer,
            int totalBytes,
            int pixelBaseWord,
            int pixelBytes,
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer uploadCopy = VkBufferCopy.calloc(1, stack);
            uploadCopy.get(0).srcOffset(0).dstOffset(0).size(totalBytes);
            VK10.vkCmdCopyBuffer(commandBuffer, uploadBuffer, sceneBuffer, uploadCopy);

            VkBufferMemoryBarrier.Buffer uploadToCompute = VkBufferMemoryBarrier.calloc(1, stack);
            uploadToCompute.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(sceneBuffer)
                    .offset(0)
                    .size(totalBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    uploadToCompute,
                    null
            );

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline());
            VK10.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    program.pipelineLayout(),
                    0,
                    stack.longs(program.descriptorSet()),
                    null
            );
            int groupsX = (width + 7) / 8;
            int groupsY = (height + 7) / 8;
            VK10.vkCmdDispatch(commandBuffer, groupsX, groupsY, 1);

            long pixelOffset = (long) pixelBaseWord * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(sceneBuffer)
                    .offset(pixelOffset)
                    .size(pixelBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    computeToCopy,
                    null
            );

            VkBufferCopy.Buffer readbackCopy = VkBufferCopy.calloc(1, stack);
            readbackCopy.get(0).srcOffset(pixelOffset).dstOffset(0).size(pixelBytes);
            VK10.vkCmdCopyBuffer(commandBuffer, sceneBuffer, readbackBuffer, readbackCopy);

            VkBufferMemoryBarrier.Buffer copyToHost = VkBufferMemoryBarrier.calloc(1, stack);
            copyToHost.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(readbackBuffer)
                    .offset(0)
                    .size(pixelBytes);
            VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    null,
                    copyToHost,
                    null
            );
        }
    }

    private static void finishValidation(
            VulkanOwnedBuffer readback,
            int width,
            int height,
            VulkanComputeProgram program,
            VulkanOwnedBuffer upload,
            VulkanOwnedBuffer scene,
            VulkanFrameCommandPool commandPool
    ) {
        try {
            int pixelBytes = width * height * Integer.BYTES;
            readback.invalidate(0, pixelBytes);
            ByteBuffer pixels = readback.mappedView();

            int hitPixels = 0;
            int missPixels = 0;
            int invalidPixels = 0;
            for (int pixel = 0; pixel < width * height; pixel++) {
                int rgba = pixels.getInt(pixel * Integer.BYTES);
                int alpha = (rgba >>> 24) & 0xFF;
                if (alpha == 255) {
                    hitPixels++;
                } else if (alpha == 64) {
                    missPixels++;
                } else {
                    invalidPixels++;
                }
            }

            if (invalidPixels != 0) {
                throw new IllegalStateException("P5 debug grid contains " + invalidPixels + " invalid/unwritten pixels");
            }

            int centerPixel = (height / 2) * width + (width / 2);
            int centerRgba = pixels.getInt(centerPixel * Integer.BYTES);
            passed = true;
            TotemLumenClient.LOGGER.info(
                    "P5 debug ray-grid PASSED: {}x{}, hits={}, misses={}, centerRGBA=0x{}",
                    width, height, hitPixels, missPixels, Integer.toHexString(centerRgba)
            );
            if (hitPixels == 0) {
                TotemLumenClient.LOGGER.warn(
                        "P5 debug grid was valid but contained no voxel hits; this can happen when the camera sees only sky/outside the 64-section debug upload"
                );
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P5 debug ray-grid FAILED", failure);
        } finally {
            closeQuietly(program);
            closeQuietly(upload);
            closeQuietly(scene);
            closeQuietly(readback);
            closeQuietly(commandPool);
        }
    }

    public static boolean passed() {
        return passed;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P5 debug-grid resource", exception);
        }
    }
}
