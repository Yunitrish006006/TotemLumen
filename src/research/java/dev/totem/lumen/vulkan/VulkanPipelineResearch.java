package dev.totem.lumen.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/** Creates one exact compute pipeline, without Minecraft, a surface, a world, or GPU dispatch. */
public final class VulkanPipelineResearch {
    private static final long MAX_CACHE = 64L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 6)
            throw new IllegalArgumentException("Expected SPIRV outputDir cacheDir empty|import deviceName [none|wait|interrupt]");
        String shutdownProbe = args.length == 6 ? args[5] : "none";
        if (!Set.of("none", "wait", "interrupt").contains(shutdownProbe))
            throw new IllegalArgumentException("Unknown shutdown probe mode");
        Path shader = Path.of(args[0]), output = Path.of(args[1]), cacheDir = Path.of(args[2]);
        boolean imported = switch (args[3]) {
            case "empty" -> false;
            case "import" -> true;
            default -> throw new IllegalArgumentException("Unknown cache mode");
        };
        byte[] spirv = Files.readAllBytes(shader);
        ShaderCompileResearch.summarize(spirv);
        Files.createDirectories(output);
        Files.createDirectories(cacheDir);
        Map<String, Object> report = ShaderCompileResearch.environment();
        report.put("spirvSha256", ShaderCompileResearch.hash(spirv));
        report.put("spirvBytes", spirv.length);
        report.put("cacheMode", args[3]);
        report.put("driverInternalCache", "uncontrolled; empty application cache is NOT fully cold");
        report.put("dispatch", false);
        report.put("shutdownProbe", shutdownProbe);
        report.put("deviceFeatures", "supported Vulkan 1.0 robustBufferAccess only; not Minecraft device parity");
        VkInstance instance = null;
        VkDevice device = null;
        long layout = 0, setLayout = 0, module = 0, cache = 0, pipeline = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int api = Math.min(VK.getInstanceVersionSupported(), VK13.VK_API_VERSION_1_3);
            if (api < VK12.VK_API_VERSION_1_2) throw new IllegalStateException("Vulkan 1.2 required");
            report.put("requestedApiVersion", api);
            Set<String> instanceExtensions = instanceExtensions(stack);
            boolean portable = instanceExtensions.contains("VK_KHR_portability_enumeration");
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Totem Lumen compile research")).apiVersion(api);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            if (portable) info.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                    .ppEnabledExtensionNames(strings(stack, List.of("VK_KHR_portability_enumeration")));
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, handle), "vkCreateInstance");
            instance = new VkInstance(handle.get(0), info);
            VkPhysicalDevice physical = selectDevice(instance, stack, args[4]);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            if (props.apiVersion() < VK12.VK_API_VERSION_1_2) throw new IllegalStateException("Device requires Vulkan 1.2");
            byte[] uuid = new byte[VK_UUID_SIZE];
            props.pipelineCacheUUID().get(uuid);
            String key = Integer.toUnsignedString(props.vendorID()) + "-" + Integer.toUnsignedString(props.deviceID())
                    + "-" + Integer.toUnsignedString(props.driverVersion()) + "-" + HexFormat.of().formatHex(uuid);
            Path cacheFile = cacheDir.resolve(key + ".bin");
            report.put("device", props.deviceNameString());
            report.put("vendorId", props.vendorID());
            report.put("deviceId", props.deviceID());
            report.put("driverVersionRaw", Integer.toUnsignedString(props.driverVersion()));
            report.put("deviceApiVersion", props.apiVersion());
            report.put("pipelineCacheUUID", HexFormat.of().formatHex(uuid));
            report.put("cacheFile", cacheFile.toAbsolutePath().toString());
            Set<String> available = deviceExtensions(physical, stack);
            List<String> extensions = new ArrayList<>();
            boolean feedbackCore = api >= VK13.VK_API_VERSION_1_3 && props.apiVersion() >= VK13.VK_API_VERSION_1_3;
            boolean feedbackSupported = feedbackCore || available.contains("VK_EXT_pipeline_creation_feedback");
            if (!feedbackCore && feedbackSupported) extensions.add("VK_EXT_pipeline_creation_feedback");
            if (available.contains("VK_KHR_portability_subset")) extensions.add("VK_KHR_portability_subset");
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(computeQueue(physical, stack)).pQueuePriorities(stack.floats(1));
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physical, supported);
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
                    .robustBufferAccess(supported.robustBufferAccess());
            report.put("robustBufferAccess", features.robustBufferAccess());
            report.put("enabledExtensions", extensions);
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .pEnabledFeatures(features);
            if (!extensions.isEmpty()) deviceInfo.ppEnabledExtensionNames(strings(stack, extensions));
            check(vkCreateDevice(physical, deviceInfo, null, handle), "vkCreateDevice");
            device = new VkDevice(handle.get(0), physical, deviceInfo);

            LongBuffer out = stack.callocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings), null, out), "descriptor layout");
            setLayout = out.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, out), "pipeline layout");
            layout = out.get(0);
            ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
            try {
                code.put(spirv).flip();
                long started = System.nanoTime();
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                        null, out), "shader module");
                module = out.get(0);
                report.put("shaderModuleNs", System.nanoTime() - started);
            } finally {
                MemoryUtil.memFree(code);
            }
            byte[] initial = new byte[0];
            long readStart = System.nanoTime();
            if (imported) {
                initial = PipelineCacheFiles.read(cacheFile);
                // Import must not silently become an empty-cache run.
                if (initial == null || initial.length < 32) throw new IllegalArgumentException("Missing or invalid cache");
            } else if (Files.exists(cacheFile)) {
                throw new IllegalArgumentException("Use a fresh research cache directory for an empty run");
            }
            report.put("cacheReadNs", System.nanoTime() - readStart);
            report.put("cacheInputBytes", initial.length);
            if (imported) report.put("cacheInputSha256", ShaderCompileResearch.hash(initial));
            ByteBuffer initialBuffer = initial.length == 0 ? null : MemoryUtil.memAlloc(initial.length);
            try {
                VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                if (initialBuffer != null) cacheInfo.pInitialData(initialBuffer.put(initial).flip());
                long started = System.nanoTime();
                check(vkCreatePipelineCache(device, cacheInfo, null, out), "pipeline cache");
                cache = out.get(0);
                report.put("cacheCreateNs", System.nanoTime() - started);
            } finally {
                if (initialBuffer != null) MemoryUtil.memFree(initialBuffer);
            }

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(layout);
            boolean feedbackRequested = VulkanPipelineCreationDiagnostics.canRequest(device, pipelineInfo);
            report.put("feedbackSupported", feedbackSupported);
            report.put("feedbackRequested", feedbackRequested);
            ShaderCompileResearch.writeJson(output.resolve("started.json"), report);
            System.out.println("PIPELINE START " + props.deviceNameString() + " " + shader.getFileName()
                    + " applicationCache=" + args[3]);
            out.put(0, 0);
            final VkDevice workerDevice = device;
            final long workerCache = cache;
            VulkanPipelineCreationDiagnostics.Result creation;
            try {
                creation = shutdownProbe.equals("none")
                        ? VulkanPipelineCreationDiagnostics.create(device, cache, pipelineInfo, out)
                        : VulkanWorkerShutdownResearch.run(
                                () -> VulkanPipelineCreationDiagnostics.create(workerDevice, workerCache, pipelineInfo, out),
                                shutdownProbe.equals("interrupt"), report);
            } finally {
                // Capture even partially successful creation before any exception can escape.
                pipeline = out.get(0);
                // The probe already recorded the restored flag. Clear it before report I/O,
                // including the failure path, so FileChannel cannot discard that evidence.
                if (!shutdownProbe.equals("none")) Thread.interrupted();
            }
            report.put("pipelineCreateNs", creation.elapsedNs());
            pipeline = out.get(0);
            int result = creation.vkResult();
            report.put("vkResult", result);
            report.put("feedbackValid", creation.feedbackValid());
            report.put("applicationCacheHit", creation.feedbackValid()
                    ? (Object) creation.applicationCacheHit() : "unknown");
            if (creation.feedbackValid()) report.put("feedbackDurationNs", creation.driverDurationNs());
            if (pipelineInfo.get(0).pNext() != 0L)
                throw new IllegalStateException("Creation diagnostics did not restore pNext");
            check(result, "vkCreateComputePipelines");
            System.out.println("PIPELINE COMPLETE ns=" + report.get("pipelineCreateNs")
                    + " applicationCacheHit=" + report.get("applicationCacheHit"));
            long started = System.nanoTime();
            PointerBuffer size = stack.mallocPointer(1);
            check(vkGetPipelineCacheData(device, cache, size, null), "cache size");
            if (size.get(0) <= 0 || size.get(0) > MAX_CACHE) throw new IllegalStateException("Cache exceeds bounded export");
            ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                check(vkGetPipelineCacheData(device, cache, size, data), "cache export");
                byte[] bytes = new byte[(int) size.get(0)];
                data.get(bytes);
                Path outputCache = output.resolve("cache").resolve(key + ".bin");
                if (Files.exists(outputCache)) throw new IllegalArgumentException("Research output cache already exists");
                PipelineCacheFiles.write(outputCache, bytes);
                report.put("cacheOutputFile", outputCache.toAbsolutePath().toString());
                report.put("cacheOutputBytes", bytes.length);
                report.put("cacheOutputSha256", ShaderCompileResearch.hash(bytes));
            } finally {
                MemoryUtil.memFree(data);
            }
            report.put("cacheSaveNs", System.nanoTime() - started);
            report.put("status", "complete");
        } catch (Throwable error) {
            report.put("status", "failed");
            report.put("failure", error.toString());
            throw error;
        } finally {
            // The probe joins its worker on success AND failure before returning/throwing.
            // Synchronous compile has returned; there are no workers or submitted commands.
            if (device != null) {
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
                if (cache != 0) vkDestroyPipelineCache(device, cache, null);
                if (module != 0) vkDestroyShaderModule(device, module, null);
                if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
                if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
                vkDestroyDevice(device, null);
                report.put("deviceDestroyed", true);
            }
            if (instance != null) vkDestroyInstance(instance, null);
            ShaderCompileResearch.writeJson(output.resolve("result.json"), report);
        }
    }

    private static Set<String> instanceExtensions(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null), "instance extension count");
        if (count.get(0) > 4096) throw new IllegalStateException("Unexpected extension count");
        try (VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0))) {
            check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, props), "instance extensions");
            Set<String> names = new HashSet<>();
            props.forEach(p -> names.add(p.extensionNameString()));
            return names;
        }
    }

    private static Set<String> deviceExtensions(VkPhysicalDevice physical, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null), "device extension count");
        if (count.get(0) > 4096) throw new IllegalStateException("Unexpected extension count");
        try (VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0))) {
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, props), "device extensions");
            Set<String> names = new HashSet<>();
            props.forEach(p -> names.add(p.extensionNameString()));
            return names;
        }
    }

    private static VkPhysicalDevice selectDevice(VkInstance instance, MemoryStack stack, String name) {
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "device count");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "devices");
        VkPhysicalDevice selected = null;
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(candidate, props);
            if (props.deviceNameString().contains(name)) {
                if (selected != null) throw new IllegalArgumentException("Device filter matches multiple GPUs");
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalArgumentException("No GPU matches " + name);
        return selected;
    }

    private static int computeQueue(VkPhysicalDevice physical, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
        VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, props);
        for (int i = 0; i < count.get(0); i++)
            if (props.get(i).queueCount() > 0 && (props.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) return i;
        throw new IllegalStateException("No compute queue");
    }

    private static PointerBuffer strings(MemoryStack stack, List<String> names) {
        PointerBuffer result = stack.mallocPointer(names.size());
        names.forEach(n -> result.put(stack.UTF8(n)));
        return result.flip();
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " VkResult=" + result);
    }
}
