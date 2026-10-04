package dev.totem.lumen.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
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
import java.nio.file.Path;
import java.util.function.Supplier;

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
    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final long MAX_CACHE_BYTES = PipelineCacheFiles.MAX_BYTES;
    private static final int PIPELINE_CACHE_UUID_BYTES = 16;

    private static VulkanDevice activeDevice;
    private static Path activeCacheFile;
    private static long activePipelineCache;
    private static boolean activeLoadedFromDisk;
    private static boolean dirty;
    private static boolean shuttingDown;
    private static boolean destroyWhenIdle;
    private static int activeCreates;

    private VulkanPipelineCacheStore() {
    }

    static int createComputePipelines(
            VulkanDevice device,
            VkComputePipelineCreateInfo.Buffer pipelineInfo,
            LongBuffer pipelineOut,
            String shaderName
    ) {
        long pipelineCache = 0L;
        boolean cacheLoadedFromDisk = false;
        boolean useCache = false;

        synchronized (LOCK) {
            if (!shuttingDown) {
                try {
                    ensureSessionCache(device, shaderName);
                    pipelineCache = activePipelineCache;
                    cacheLoadedFromDisk = activeLoadedFromDisk;
                    useCache = pipelineCache != 0L;
                    if (useCache) activeCreates++;
                } catch (Throwable cacheFailure) {
                    TotemLumenClient.LOGGER.warn(
                            "Persistent Vulkan pipeline cache unavailable for {}; compiling without cache",
                            shaderName,
                            cacheFailure
                    );
                }
            }
        }

        long selectedCache = useCache ? pipelineCache : 0L;
        var creation = runCreation(useCache, shaderName, () -> VulkanPipelineCreationDiagnostics.create(
                device.vkDevice(), selectedCache, pipelineInfo, pipelineOut));
        int result = creation.vkResult();

        TotemLumenClient.LOGGER.info(
                "Vulkan pipeline creation {}: shader={}, cacheSource={}, applicationCacheHit={}, "
                        + "feedbackRequested={}, feedbackValid={}, elapsed={} ms, driverDurationNs={}",
                result == VK10.VK_SUCCESS ? "COMPLETE" : "FAILED(" + result + ")",
                shaderName,
                useCache ? (cacheLoadedFromDisk ? "loaded" : "empty") : "disabled",
                creation.hitLabel(),
                creation.feedbackRequested(),
                creation.feedbackValid(),
                creation.elapsedNs() / 1_000_000L,
                creation.feedbackValid() ? Long.toString(creation.driverDurationNs()) : "unknown"
        );

        return result;
    }

    /** Paired with admission under LOCK; also releases the reservation when Java/native dispatch throws. */
    static VulkanPipelineCreationDiagnostics.Result runCreation(
            boolean useCache, String shaderName, Supplier<VulkanPipelineCreationDiagnostics.Result> operation) {
        VulkanPipelineCreationDiagnostics.Result result = null;
        Throwable failure = null;
        try {
            result = operation.get();
            return result;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (useCache) {
                try {
                    finishCreation(shaderName, result != null && result.vkResult() == VK10.VK_SUCCESS);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) throw cleanupFailure;
                    if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private static void finishCreation(String shaderName, boolean successful) {
        synchronized (LOCK) {
            try {
                activeCreates--;
                if (successful) {
                    dirty = true;
                }

                if (activeCreates == 0) {
                    if (dirty) {
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
                    if (destroyWhenIdle) {
                        closeSessionCacheLocked("deferred shutdown");
                    }
                } else if (successful) {
                    TotemLumenClient.LOGGER.info(
                            "Deferring Vulkan pipeline cache save after {} until {} concurrent build(s) finish",
                            shaderName,
                            activeCreates
                    );
                }
            } finally {
                LOCK.notifyAll();
            }
        }
    }

    static void shutdown() {
        synchronized (LOCK) {
            shuttingDown = true;
            if (activePipelineCache == 0L) {
                resetSessionState();
                return;
            }

            if (activeCreates > 0) {
                destroyWhenIdle = true;
                TotemLumenClient.LOGGER.info(
                        "Vulkan pipeline cache shutdown deferred until {} active pipeline build(s) finish",
                        activeCreates
                );
                return;
            }

            closeSessionCacheLocked("session shutdown");
        }
    }

    private static void ensureSessionCache(VulkanDevice device, String shaderName) throws IOException {
        if (activePipelineCache != 0L
                && activeDevice != null
                && activeDevice.vkDevice() == device.vkDevice()) {
            return;
        }

        if (activePipelineCache != 0L) {
            if (activeCreates > 0) {
                throw new IllegalStateException(
                        "Cannot switch Vulkan pipeline-cache device while "
                                + activeCreates
                                + " pipeline build(s) are active"
                );
            }
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
                        "Persistent Vulkan pipeline cache rejected by driver for {}; ignoring loaded data from {} and retrying empty",
                        shaderName,
                        cacheFile.getFileName(),
                        cachedCreateFailure
                );
                // Another process may already have replaced the rejected blob. Do not delete its file.
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
                loaded ? "LOADED" : "EMPTY",
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
        byte[] bytes;
        try {
            bytes = PipelineCacheFiles.read(cacheFile);
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn(
                    "Ignoring unreadable or invalid Vulkan pipeline cache for {}: {}; retrying empty",
                    shaderName,
                    cacheFile.getFileName(),
                    failure
            );
            return null;
        }
        if (bytes == null) return null;
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
            PipelineCacheFiles.write(cacheFile, bytes);

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

    private static void closeSessionCacheLocked(String reason) {
        if (activePipelineCache == 0L) {
            resetSessionState();
            return;
        }

        if (dirty) {
            try {
                persistActiveCache(reason);
            } catch (Throwable persistFailure) {
                TotemLumenClient.LOGGER.warn(
                        "Failed to persist shared Vulkan pipeline cache during {}",
                        reason,
                        persistFailure
                );
            }
        }

        VK10.vkDestroyPipelineCache(activeDevice.vkDevice(), activePipelineCache, null);
        TotemLumenClient.LOGGER.info(
                "Vulkan pipeline cache session CLOSED: reason={}, file={}",
                reason,
                activeCacheFile == null ? "<none>" : activeCacheFile.getFileName()
        );
        resetSessionState();
    }

    private static void resetSessionState() {
        activeDevice = null;
        activeCacheFile = null;
        activePipelineCache = 0L;
        activeLoadedFromDisk = false;
        dirty = false;
        destroyWhenIdle = false;
        activeCreates = 0;
    }

}
