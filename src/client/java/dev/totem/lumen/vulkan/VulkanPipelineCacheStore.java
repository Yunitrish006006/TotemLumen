package dev.totem.lumen.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.totem.lumen.TotemLumenClient;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Persistent host-side Vulkan pipeline cache used by Totem Lumen compute pipelines.
 *
 * <p>Each pipeline build opens a short-lived VkPipelineCache from the last persisted blob, passes it
 * to vkCreateComputePipelines, writes the updated opaque cache data atomically, and destroys the
 * cache object before returning. This keeps cache lifetime independent from Minecraft's VkDevice
 * shutdown order while still allowing the base and P16 pipelines to accumulate into one driver
 * cache file.</p>
 */
final class VulkanPipelineCacheStore {
    private static final Object LOCK = new Object();
    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int PIPELINE_CACHE_UUID_BYTES = 16;

    private VulkanPipelineCacheStore() {
    }

    static int createComputePipelines(
            VulkanDevice device,
            VkComputePipelineCreateInfo.Buffer pipelineInfo,
            LongBuffer pipelineOut,
            String shaderName
    ) {
        synchronized (LOCK) {
            Path cacheFile;
            try {
                cacheFile = cacheFile(device);
            } catch (Throwable identityFailure) {
                TotemLumenClient.LOGGER.warn(
                        "Persistent Vulkan pipeline cache identity lookup failed for {}; compiling without cache",
                        shaderName,
                        identityFailure
                );
                return VK10.vkCreateComputePipelines(device.vkDevice(), 0L, pipelineInfo, null, pipelineOut);
            }

            ByteBuffer initialData = null;
            long pipelineCache = 0L;
            boolean loaded = false;
            try {
                initialData = loadCache(cacheFile, shaderName);
                loaded = initialData != null && initialData.hasRemaining();

                try {
                    pipelineCache = createPipelineCache(device, initialData);
                } catch (Throwable cachedCreateFailure) {
                    if (!loaded) {
                        throw cachedCreateFailure;
                    }
                    TotemLumenClient.LOGGER.warn(
                            "Persistent Vulkan pipeline cache rejected by driver for {}; discarding {} and retrying empty",
                            shaderName,
                            cacheFile.getFileName(),
                            cachedCreateFailure
                    );
                    deleteQuietly(cacheFile);
                    pipelineCache = createPipelineCache(device, null);
                    loaded = false;
                }

                TotemLumenClient.LOGGER.info(
                        "Vulkan pipeline cache {}: shader={}, file={}, bytes={}",
                        loaded ? "HIT" : "MISS",
                        shaderName,
                        cacheFile.getFileName(),
                        initialData == null ? 0 : initialData.remaining()
                );

                int result = VK10.vkCreateComputePipelines(
                        device.vkDevice(),
                        pipelineCache,
                        pipelineInfo,
                        null,
                        pipelineOut
                );
                if (result == VK10.VK_SUCCESS) {
                    try {
                        persistCache(device, pipelineCache, cacheFile, shaderName);
                    } catch (Throwable persistFailure) {
                        TotemLumenClient.LOGGER.warn(
                                "Failed to persist Vulkan pipeline cache after {}; renderer remains usable",
                                shaderName,
                                persistFailure
                        );
                    }
                }
                return result;
            } catch (Throwable cacheFailure) {
                TotemLumenClient.LOGGER.warn(
                        "Persistent Vulkan pipeline cache unavailable for {}; compiling without cache",
                        shaderName,
                        cacheFailure
                );
                return VK10.vkCreateComputePipelines(device.vkDevice(), 0L, pipelineInfo, null, pipelineOut);
            } finally {
                if (pipelineCache != 0L) {
                    VK10.vkDestroyPipelineCache(device.vkDevice(), pipelineCache, null);
                }
                if (initialData != null) {
                    MemoryUtil.memFree(initialData);
                }
            }
        }
    }

    private static long createPipelineCache(VulkanDevice device, ByteBuffer initialData) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initialData != null && initialData.hasRemaining()) {
                createInfo.pInitialData(initialData);
            }
            LongBuffer cacheOut = stack.mallocLong(1);
            int result = VK10.vkCreatePipelineCache(device.vkDevice(), createInfo, null, cacheOut);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreatePipelineCache failed: " + result);
            }
            return cacheOut.get(0);
        }
    }

    private static ByteBuffer loadCache(Path cacheFile, String shaderName) throws IOException {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        long size = Files.size(cacheFile);
        if (size <= 0L || size > MAX_CACHE_BYTES || size > Integer.MAX_VALUE) {
            TotemLumenClient.LOGGER.warn(
                    "Ignoring invalid Vulkan pipeline cache size for {}: {} bytes ({})",
                    shaderName,
                    size,
                    cacheFile.getFileName()
            );
            deleteQuietly(cacheFile);
            return null;
        }

        byte[] bytes = Files.readAllBytes(cacheFile);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        return data;
    }

    private static void persistCache(
            VulkanDevice device,
            long pipelineCache,
            Path cacheFile,
            String shaderName
    ) throws IOException {
        long size;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizeOut = stack.mallocPointer(1);
            int result = VK10.vkGetPipelineCacheData(device.vkDevice(), pipelineCache, sizeOut, null);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkGetPipelineCacheData(size) failed: " + result);
            }
            size = sizeOut.get(0);
        }

        if (size <= 0L || size > MAX_CACHE_BYTES || size > Integer.MAX_VALUE) {
            TotemLumenClient.LOGGER.warn(
                    "Skipping Vulkan pipeline cache write after {} because driver returned {} bytes",
                    shaderName,
                    size
            );
            return;
        }

        ByteBuffer data = MemoryUtil.memAlloc((int) size);
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer sizeOut = stack.mallocPointer(1);
                sizeOut.put(0, size);
                int result = VK10.vkGetPipelineCacheData(device.vkDevice(), pipelineCache, sizeOut, data);
                if (result != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkGetPipelineCacheData(data) failed: " + result);
                }
                int actualSize = Math.toIntExact(sizeOut.get(0));
                data.limit(actualSize);
                data.position(0);
            }

            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            Files.createDirectories(cacheFile.getParent());
            Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.write(
                    temporary,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        cacheFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }

            TotemLumenClient.LOGGER.info(
                    "Vulkan pipeline cache SAVED: shader={}, file={}, bytes={}",
                    shaderName,
                    cacheFile.getFileName(),
                    bytes.length
            );
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static Path cacheFile(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
            StringBuilder uuid = new StringBuilder(PIPELINE_CACHE_UUID_BYTES * 2);
            for (int index = 0; index < PIPELINE_CACHE_UUID_BYTES; index++) {
                uuid.append(String.format("%02x", properties.pipelineCacheUUID(index) & 0xff));
            }
            String fileName = "v" + CACHE_SCHEMA_VERSION
                    + "-vendor-" + Integer.toUnsignedString(properties.vendorID(), 16)
                    + "-device-" + Integer.toUnsignedString(properties.deviceID(), 16)
                    + "-driver-" + Integer.toUnsignedString(properties.driverVersion(), 16)
                    + "-uuid-" + uuid
                    + ".bin";
            return FabricLoader.getInstance()
                    .getGameDir()
                    .resolve("cache")
                    .resolve("totem-lumen")
                    .resolve("vulkan-pipelines")
                    .resolve(fileName);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.debug("Failed to delete stale Vulkan pipeline cache {}", path, failure);
        }
    }
}
