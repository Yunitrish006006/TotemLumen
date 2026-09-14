package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import dev.totem.lumen.integration.SceneExtractionBridge;
import dev.totem.lumen.scene.FrameSnapshot;
import dev.totem.lumen.scene.SectionSnapshot;
import dev.totem.lumen.scene.SectionVoxelData;
import dev.totem.lumen.vulkan.resource.VulkanOwnedBuffer;
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
 * P4 correctness gate for real Minecraft voxel data.
 *
 * <p>Two rays are traced by the same Vulkan compute dispatch:
 * <ol>
 *     <li>a deterministic synthetic ray from an air voxel into an adjacent known-solid voxel;</li>
 *     <li>the latest camera-center ray for diagnostic logging.</li>
 * </ol>
 * The first ray makes the test deterministic even when the player is looking at the sky.</p>
 */
public final class P4DdaSmokeTest {
    private static final int MAX_SECTIONS = 64;
    private static final int HEADER_WORDS = 64;
    private static final int SECTION_META_WORDS = 4;
    private static final int OUTPUT_BASE_WORD = 32;
    private static final int RESULT_WORDS = 12;
    private static final int RAY_COUNT = 2;
    private static final int RESULT_BYTES = RAY_COUNT * RESULT_WORDS * Integer.BYTES;
    private static final int MAX_STEPS = 512;
    private static final float MAX_DISTANCE = 256.0f;

    private static final String SHADER = """
            #version 450
            layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
            layout(set = 0, binding = 0, std430) buffer SceneBuffer {
                uint data[];
            } scene;

            const uint SECTION_TABLE_BASE = 64u;
            const uint SECTION_META_WORDS = 4u;
            const uint OUTPUT_BASE = 32u;
            const uint RESULT_WORDS = 12u;

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

            void writeResult(
                uint rayIndex,
                uint hit,
                uint materialId,
                ivec3 voxel,
                ivec3 normal,
                float distance,
                uint steps
            ) {
                uint base = OUTPUT_BASE + rayIndex * RESULT_WORDS;
                scene.data[base] = hit;
                scene.data[base + 1u] = materialId;
                scene.data[base + 2u] = uint(voxel.x);
                scene.data[base + 3u] = uint(voxel.y);
                scene.data[base + 4u] = uint(voxel.z);
                scene.data[base + 5u] = uint(normal.x);
                scene.data[base + 6u] = uint(normal.y);
                scene.data[base + 7u] = uint(normal.z);
                scene.data[base + 8u] = floatBitsToUint(distance);
                scene.data[base + 9u] = steps;
                scene.data[base + 10u] = 0u;
                scene.data[base + 11u] = 0u;
            }

            void traceRay(uint rayIndex, vec3 origin, vec3 direction) {
                float directionLength = length(direction);
                if (directionLength < 0.000001) {
                    writeResult(rayIndex, 0u, 0u, ivec3(0), ivec3(0), 0.0, 0u);
                    return;
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
                uint maxSteps = scene.data[3];
                float maxDistance = uintBitsToFloat(scene.data[4]);

                for (uint iteration = 0u; iteration < maxSteps; iteration++) {
                    uint materialId = materialAt(voxel);
                    if (materialId != 0u) {
                        writeResult(rayIndex, 1u, materialId, voxel, normal, distance, iteration);
                        return;
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

                writeResult(rayIndex, 0u, 0u, voxel, normal, distance, maxSteps);
            }

            void main() {
                uint rayIndex = gl_GlobalInvocationID.x;
                if (rayIndex >= 2u) return;
                uint rayBase = 8u + rayIndex * 8u;
                vec3 origin = vec3(
                    uintBitsToFloat(scene.data[rayBase]),
                    uintBitsToFloat(scene.data[rayBase + 1u]),
                    uintBitsToFloat(scene.data[rayBase + 2u])
                );
                vec3 direction = vec3(
                    uintBitsToFloat(scene.data[rayBase + 3u]),
                    uintBitsToFloat(scene.data[rayBase + 4u]),
                    uintBitsToFloat(scene.data[rayBase + 5u])
                );
                traceRay(rayIndex, origin, direction);
            }
            """;

    private static boolean attempted;
    private static volatile boolean passed;

    private P4DdaSmokeTest() {
    }

    public static void runOnceOnRenderThread() {
        if (attempted || !P4ComputeSmokeTest.passed()) {
            return;
        }

        FrameSnapshot frame = SceneExtractionBridge.latestFrame();
        if (frame == null || !frame.dimensionId().equals(SceneExtractionBridge.scene().activeDimension())) {
            return;
        }

        List<SectionSnapshot> candidates = nearestSections(frame);
        if (candidates.isEmpty()) {
            return;
        }

        Probe probe = findDeterministicProbe(candidates);
        if (probe == null) {
            return;
        }

        VulkanCapabilities capabilities = RendererBootstrapAccess.capabilities();
        var device = MinecraftVulkanBridge.currentDevice();
        if (capabilities == null || device == null || !capabilities.canUseMinecraftFrameSubmissionForCompute()) {
            return;
        }

        attempted = true;
        submit(frame, candidates, probe, device, capabilities);
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

    private static Probe findDeterministicProbe(List<SectionSnapshot> sections) {
        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
        };

        for (SectionSnapshot snapshot : sections) {
            SectionVoxelData voxels = snapshot.voxels();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int materialId = voxels.materialId(x, y, z);
                        if (materialId == 0) continue;

                        for (int[] direction : directions) {
                            int airX = x - direction[0];
                            int airY = y - direction[1];
                            int airZ = z - direction[2];
                            if (airX < 0 || airX >= 16 || airY < 0 || airY >= 16 || airZ < 0 || airZ >= 16) {
                                continue;
                            }
                            if (voxels.materialId(airX, airY, airZ) != 0) {
                                continue;
                            }

                            int baseX = snapshot.key().x() * 16;
                            int baseY = snapshot.key().y() * 16;
                            int baseZ = snapshot.key().z() * 16;
                            return new Probe(
                                    baseX + airX + 0.5f,
                                    baseY + airY + 0.5f,
                                    baseZ + airZ + 0.5f,
                                    direction[0], direction[1], direction[2],
                                    baseX + x, baseY + y, baseZ + z,
                                    materialId
                            );
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void submit(
            FrameSnapshot frame,
            List<SectionSnapshot> sections,
            Probe probe,
            com.mojang.blaze3d.vulkan.VulkanDevice device,
            VulkanCapabilities capabilities
    ) {
        int sectionCount = sections.size();
        int voxelBaseWord = HEADER_WORDS + sectionCount * SECTION_META_WORDS;
        int totalWords = voxelBaseWord + sectionCount * SectionVoxelData.VOXEL_COUNT;
        int totalBytes = Math.multiplyExact(totalWords, Integer.BYTES);

        VulkanFrameCommandPool commandPool = null;
        VulkanOwnedBuffer upload = null;
        VulkanOwnedBuffer scene = null;
        VulkanOwnedBuffer readback = null;
        VulkanComputeProgram program = null;
        try {
            commandPool = new VulkanFrameCommandPool(device);
            upload = VulkanOwnedBuffer.createUpload(device, totalBytes);
            scene = VulkanOwnedBuffer.createStorage(device, totalBytes);
            readback = VulkanOwnedBuffer.createReadback(device, RESULT_BYTES);
            packScene(upload.mappedView(), frame, sections, probe, voxelBaseWord, totalWords);
            upload.flush(0, totalBytes);

            program = VulkanComputeProgram.create(device, "totem_lumen_dda.comp", SHADER, scene);

            VulkanFrameCommandPool finalCommandPool = commandPool;
            VulkanOwnedBuffer finalUpload = upload;
            VulkanOwnedBuffer finalScene = scene;
            VulkanOwnedBuffer finalReadback = readback;
            VulkanComputeProgram finalProgram = program;

            VulkanFrameComputeBatch batch = VulkanFrameComputeBatch.begin(device, capabilities, commandPool);
            recordCommands(
                    batch.commandBuffer(), program,
                    upload.vkBuffer(), scene.vkBuffer(), readback.vkBuffer(),
                    totalBytes
            );
            batch.finishAndEnqueue(() -> finishValidation(
                    finalReadback, probe, finalProgram, finalUpload, finalScene, finalCommandPool
            ));

            TotemLumenClient.LOGGER.info(
                    "P4 DDA smoke test submitted: sections={}, bytes={} KiB, syntheticTarget=({}, {}, {}) material={}, camera=({}, {}, {})",
                    sectionCount,
                    totalBytes / 1024,
                    probe.voxelX(), probe.voxelY(), probe.voxelZ(), probe.materialId(),
                    String.format("%.2f", frame.cameraX()),
                    String.format("%.2f", frame.cameraY()),
                    String.format("%.2f", frame.cameraZ())
            );
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P4 DDA smoke test setup failed", failure);
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
            Probe probe,
            int voxelBaseWord,
            int totalWords
    ) {
        for (int word = 0; word < totalWords; word++) {
            putWord(buffer, word, 0);
        }
        putWord(buffer, 1, sections.size());
        putWord(buffer, 2, voxelBaseWord);
        putWord(buffer, 3, MAX_STEPS);
        putWord(buffer, 4, Float.floatToRawIntBits(MAX_DISTANCE));
        putWord(buffer, 5, RAY_COUNT);

        putRay(buffer, 8, probe.originX(), probe.originY(), probe.originZ(), probe.dirX(), probe.dirY(), probe.dirZ());
        float[] cameraDirection = cameraForward(frame);
        putRay(
                buffer, 16,
                (float) frame.cameraX(), (float) frame.cameraY(), (float) frame.cameraZ(),
                cameraDirection[0], cameraDirection[1], cameraDirection[2]
        );

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

    private static float[] cameraForward(FrameSnapshot frame) {
        float x = frame.rotationX();
        float y = frame.rotationY();
        float z = frame.rotationZ();
        float w = frame.rotationW();
        float dx = -2.0f * (x * z + w * y);
        float dy = 2.0f * (w * x - y * z);
        float dz = -1.0f + 2.0f * (x * x + y * y);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.000001f) {
            return new float[]{0.0f, 0.0f, -1.0f};
        }
        return new float[]{dx / length, dy / length, dz / length};
    }

    private static void putRay(
            ByteBuffer buffer, int base,
            float ox, float oy, float oz,
            float dx, float dy, float dz
    ) {
        putWord(buffer, base, Float.floatToRawIntBits(ox));
        putWord(buffer, base + 1, Float.floatToRawIntBits(oy));
        putWord(buffer, base + 2, Float.floatToRawIntBits(oz));
        putWord(buffer, base + 3, Float.floatToRawIntBits(dx));
        putWord(buffer, base + 4, Float.floatToRawIntBits(dy));
        putWord(buffer, base + 5, Float.floatToRawIntBits(dz));
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
            int totalBytes
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
            VK10.vkCmdDispatch(commandBuffer, RAY_COUNT, 1, 1);

            long resultOffset = (long) OUTPUT_BASE_WORD * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer computeToCopy = VkBufferMemoryBarrier.calloc(1, stack);
            computeToCopy.get(0)
                    .sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(sceneBuffer)
                    .offset(resultOffset)
                    .size(RESULT_BYTES);
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
            readbackCopy.get(0).srcOffset(resultOffset).dstOffset(0).size(RESULT_BYTES);
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
                    .size(RESULT_BYTES);
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
            Probe expected,
            VulkanComputeProgram program,
            VulkanOwnedBuffer upload,
            VulkanOwnedBuffer scene,
            VulkanFrameCommandPool commandPool
    ) {
        try {
            readback.invalidate(0, RESULT_BYTES);
            ByteBuffer values = readback.mappedView();
            RayResult synthetic = readResult(values, 0);
            RayResult camera = readResult(values, 1);

            if (!synthetic.hit()) {
                throw new IllegalStateException("Synthetic DDA ray missed its known target: " + synthetic);
            }
            if (synthetic.materialId() != expected.materialId()
                    || synthetic.voxelX() != expected.voxelX()
                    || synthetic.voxelY() != expected.voxelY()
                    || synthetic.voxelZ() != expected.voxelZ()) {
                throw new IllegalStateException(
                        "Synthetic DDA ray hit wrong voxel/material: expected voxel=("
                                + expected.voxelX() + ", " + expected.voxelY() + ", " + expected.voxelZ()
                                + ") material=" + expected.materialId() + ", actual=" + synthetic
                );
            }

            passed = true;
            TotemLumenClient.LOGGER.info(
                    "P4 DDA smoke test PASSED: voxel=({}, {}, {}), material={}, normal=({}, {}, {}), distance={}, steps={}",
                    synthetic.voxelX(), synthetic.voxelY(), synthetic.voxelZ(), synthetic.materialId(),
                    synthetic.normalX(), synthetic.normalY(), synthetic.normalZ(),
                    synthetic.distance(), synthetic.steps()
            );
            if (camera.hit()) {
                TotemLumenClient.LOGGER.info(
                        "P4 camera-center DDA HIT: voxel=({}, {}, {}), material={}, normal=({}, {}, {}), distance={}, steps={}",
                        camera.voxelX(), camera.voxelY(), camera.voxelZ(), camera.materialId(),
                        camera.normalX(), camera.normalY(), camera.normalZ(), camera.distance(), camera.steps()
                );
            } else {
                TotemLumenClient.LOGGER.info(
                        "P4 camera-center DDA MISS within {} blocks / {} steps (expected when looking at sky or outside uploaded debug sections)",
                        MAX_DISTANCE, MAX_STEPS
                );
            }
        } catch (Throwable failure) {
            TotemLumenClient.LOGGER.error("P4 DDA smoke test FAILED", failure);
        } finally {
            closeQuietly(program);
            closeQuietly(upload);
            closeQuietly(scene);
            closeQuietly(readback);
            closeQuietly(commandPool);
        }
    }

    private static RayResult readResult(ByteBuffer values, int rayIndex) {
        int base = rayIndex * RESULT_WORDS * Integer.BYTES;
        return new RayResult(
                values.getInt(base) != 0,
                values.getInt(base + 4),
                values.getInt(base + 8),
                values.getInt(base + 12),
                values.getInt(base + 16),
                values.getInt(base + 20),
                values.getInt(base + 24),
                values.getInt(base + 28),
                Float.intBitsToFloat(values.getInt(base + 32)),
                Integer.toUnsignedLong(values.getInt(base + 36))
        );
    }

    public static boolean passed() {
        return passed;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception exception) {
            TotemLumenClient.LOGGER.warn("Failed to close P4 DDA smoke-test resource", exception);
        }
    }

    private record Probe(
            float originX, float originY, float originZ,
            float dirX, float dirY, float dirZ,
            int voxelX, int voxelY, int voxelZ,
            int materialId
    ) {
    }

    private record RayResult(
            boolean hit,
            int materialId,
            int voxelX, int voxelY, int voxelZ,
            int normalX, int normalY, int normalZ,
            float distance,
            long steps
    ) {
    }

    /** Small indirection to keep the test independent from RendererBootstrap internals. */
    private static final class RendererBootstrapAccess {
        private RendererBootstrapAccess() {
        }

        private static VulkanCapabilities capabilities() {
            return dev.totem.lumen.render.RendererBootstrap.vulkanCapabilities();
        }
    }
}
