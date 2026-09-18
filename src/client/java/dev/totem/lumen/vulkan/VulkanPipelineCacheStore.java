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
 * Persistent session-wide Vulkan pipeline cache used by all Totem Lumen compute pipelines.
 *
 * <p>The previous implementation recreated a VkPipelineCache from disk for every individual
 * pipeline. This store now loads one driver cache per VkDevice, keeps it alive across bootstrap,
 * full lighting, entity and reflection compilation, and persists the accumulated blob after each
 * successful pipeline plus once at shutdown.</p>
 */
final class VulkanPipelineCacheStore {
    private static final Object LOCK = new Object();
    private static final int CACHE_SCHEMA_VERSION = 2;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int PIPELINE_CACHE_UUID_BYTES = 16;

    private static VulkanDevice activeDevice;
    private static Path activeCacheFile;
    private static long activePipelineCache;
    private static boolean activeLoadedFromDisk;
    private static boolean dirty;

    private VulkanPipelineCacheStore() {
    }

    static int createComputePipelines(
            VulkanDevice device,
            VkComputePipelineCreateInfo.Buffer pipelineInfo,
            LongBuffer pipelineOut,
            String shaderName
    ) {
        synchronized (LOCK) {
            try {
                ensureSessionCache(device, shaderName);
            } catch (Throwable cacheFailure) {
                TotemLumenClient.LOGGER.warn(
                        "Persistent Vulkan pipeline cache unavailable for {}; compiling without cache",
                        shaderName,
                        cacheFailure
                );
                return createWithoutCache(device, pipelineInfo, pipelineOut, shaderName);
            }

            long startedAt = System.nanoTime();
            int result = VK10.vkCreateComputePipelines(
                    device.vkDevice(),
                    activePipelineCache,
                    pipelineInfo,
                    null,
                    pipelineOut
            );
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            TotemLumenClient.LOGGER.info(
                    "Vulkan pipeline creation {}: shader={}, sessionCache={}, elapsed={} ms",
                    result == VK10.VK_SUCCESS ? "COMPLETE" : "FAILED(" + result + ")",
                    shaderName,
                    activeLoadedFromDisk ? "warm" : "cold",
                    elapsedMs
            );

            if (result == VK10.VK_SUCCESS) {
                dirty = true;
                try {
                    persistActiveCache(shaderName);
                } catch (Throwable persistFailure) {
                    TotemLumenClient.LOGGER.warn(
                            "Failed to persist shared Vulkan pipeline cache after {}; renderer remains usable",
                            shaderName,
                            persistFailure
                    );
                }
            }
            return result;
        }
    }

    static void shutdown() {
        synchronized (LOCK) {
            if (activePipelineCache == 0L) {
                resetSessionState();
                return;
            }

            if (dirty) {
                try {
                    persistActiveCache("session shutdown");
                } catch (Throwable persistFailure) {
                    TotemLumenClient.LOGGER.warn(
                            "Failed to persist shared Vulkan pipeline cache during shutdown",
                            persistFailure
                    );
                }
            }

            VK10.vkDestroyPipelineCache(activeDevice.vkDevice(), activePipelineCache, null);
            TotemLumenClient.LOGGER.info(
                    "Vulkan pipeline cache session CLOSED: file={}",
                    activeCacheFile == null ? "<none>" : activeCacheFile.getFileName()
            );
            resetSessionState();
        }
    }

    private static void ensureSessionCache(VulkanDevice device, String shaderName) throws IOException {
        if (activePipelineCache != 0L
                && activeDevice != null
                && activeDevice.vkDevice() == device.vkDevice()) {
            return;
        }

        if (activePipelineCache != 0L) {
            if (dirty) {
                try {
                    persistActiveCache("device switch");
                } catch (Throwable persistFailure) {
                    TotemLumenClient.LOGGER.warn(
                            "Failed to persist old Vulkan pipeline cache before device switch",
                            persistFailure
                    );
                }
            }
            VK10.vkDestroyPipelineCache(activeDevice.vkDevice(), activePipelineCache, null);
            resetSessionState();
        }

        Path cacheFile = cacheFile(device);
        ByteBuffer initialData = null;
        long pipelineCache;
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
        } finally {
            if (initialData != null) {
                MemoryUtil.memFree(initialData);
            }
        }

        activeDevice = device;
        activeCacheFile = cacheFile;
        activePipelineCache = pipelineCache;
        activeLoadedFromDisk = loaded;
        dirty = false;

        TotemLumenClient.LOGGER.info(
                "Vulkan pipeline cache SESSION {}: shader={}, file={}",
                loaded ? "HIT" : "MISS",
                shaderName,
                cacheFile.getFileName()
        );
    }

    private static int createWithoutCache(
            VulkanDevice device,
            VkComputePipelineCreateInfo.Buffer pipelineInfo,
            LongBuffer pipelineOut,
            String shaderName
    ) {
        long startedAt = System.nanoTime();
        int result = VK10.vkCreateComputePipelines(
                device.vkDevice(),
                0L,
                pipelineInfo,
                null,
                pipelineOut
        );
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        TotemLumenClient.LOGGER.info(
                "Vulkan pipeline creation without cache {}: shader={}, elapsed={} ms",
                result == VK10.VK_SUCCESS ? "COMPLETE" : "FAILED(" + result + ")",
                shaderName,
                elapsedMs
        );
        return result;
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

    private static void persistActiveCache(String reason) throws IOException {
        if (activePipelineCache == 0L || activeDevice == null || activeCacheFile == null) {
            return;
        }
        persistCache(activeDevice, activePipelineCache, activeCacheFile, reason);
        dirty = false;
    }

    private static void persistCache(
            VulkanDevice device,
            long pipelineCache,
            Path cacheFile,
            String reason
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
                    reason,
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
                    "Vulkan pipeline cache SAVED: reason={}, file={}, bytes={}",
                    reason,
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

    private static void resetSessionState() {
        activeDevice = null;
        activeCacheFile = null;
        activePipelineCache = 0L;
        activeLoadedFromDisk = false;
        dirty = false;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.debug("Failed to delete stale Vulkan pipeline cache {}", path, failure);
        }
    }
}
