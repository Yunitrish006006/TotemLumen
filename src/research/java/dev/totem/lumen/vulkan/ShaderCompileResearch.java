package dev.totem.lumen.vulkan;

import com.google.gson.GsonBuilder;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Developer-only compiler experiment. Not included in the published mod source sets. */
public final class ShaderCompileResearch {
    static final int TARGET = 4_202_496; // Same Vulkan 1.2 target as production.
    static final String BOOTSTRAP = "totem_lumen_p12_one_bounce_gi.comp";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected new export directory [variant]");
        String variant = args.length == 2 ? args[1] : "baseline";
        if (!variant.equals("baseline") && !variant.equals(FilteredTraceResearch.VARIANT)
                && !variant.equals(DirectTemporalResearch.VARIANT))
            throw new IllegalArgumentException("Unknown research variant: " + variant);
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        if (Files.exists(output.resolve("manifest.json"))) throw new IllegalArgumentException("Export already exists");
        Map<String, Object> manifest = environment();
        manifest.put("exportProvenance", exportProvenance());
        manifest.put("variant", variant);
        manifest.put("targetEnv", TARGET);
        manifest.put("optimization", "shaderc O0; production bootstrap/full/reflection policy");
        manifest.put("cache", "shaderc called directly; no application SPIR-V cache");
        long nativeLoadStart = System.nanoTime();
        manifest.put("shadercLibrary", org.lwjgl.util.shaderc.Shaderc.getLibrary().getName());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer version = stack.mallocInt(1), revision = stack.mallocInt(1);
            Shaderc.shaderc_get_spv_version(version, revision);
            manifest.put("shadercSupportedSpirvVersion", version.get(0));
            manifest.put("shadercSupportedSpirvRevision", revision.get(0));
        }
        manifest.put("shadercLoadAndVersionQueryNs", System.nanoTime() - nativeLoadStart);
        Path library = Path.of(Shaderc.getLibrary().getName());
        manifest.put("shadercNativeSha256", Files.isRegularFile(library) ? hash(Files.readAllBytes(library)) : "unavailable");
        long start = System.nanoTime();
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc initialization failed");
        manifest.put("compilerInitializeNs", System.nanoTime() - start);
        Map<String, Object> shaders = new LinkedHashMap<>();
        try {
            shaders.put("bootstrap", export(compiler, output, "bootstrap", BOOTSTRAP, P5BootstrapShader::source, "baseline"));
            shaders.put("full", export(compiler, output, "full", P12FullBasePipeline.SHADER_NAME,
                    P12FullBasePipeline::buildSourceForVerification, variant));
            shaders.put("reflection", export(compiler, output, "reflection", P16ReflectionPassShader.SHADER_NAME,
                    () -> P14EFluidOpticsPatch.apply(P17ShaderIntegration.apply(
                            P14EFluidShaderPatch.apply(P16ReflectionPassShader.build()))),
                    variant.equals(DirectTemporalResearch.VARIANT) ? "baseline" : variant));
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        manifest.put("shaders", shaders);
        writeJson(output.resolve("manifest.json"), manifest);
        System.out.println("EXPORT COMPLETE " + output.toAbsolutePath());
    }

    private static Map<String, Object> export(long compiler, Path output, String id, String name,
                                              Supplier<String> builder, String variant) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        long start = System.nanoTime();
        String baseline = builder.get();
        row.put("assembleNs", System.nanoTime() - start);
        start = System.nanoTime();
        String source = switch (variant) {
            case "baseline" -> baseline;
            case FilteredTraceResearch.VARIANT -> FilteredTraceResearch.apply(baseline);
            case DirectTemporalResearch.VARIANT -> DirectTemporalResearch.apply(baseline);
            default -> throw new IllegalArgumentException("Unknown research variant: " + variant);
        };
        row.put("transformNs", System.nanoTime() - start);
        row.put("variant", variant);
        row.put("baselineSourceSha256", hash(baseline.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!variant.equals("baseline"))
            Files.writeString(output.resolve(id + ".baseline.comp"), baseline, StandardOpenOption.CREATE_NEW);
        byte[] glsl = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        row.put("shaderName", name);
        row.put("sourceBytes", glsl.length);
        row.put("sourceSha256", hash(glsl));
        Files.write(output.resolve(id + ".comp"), glsl, StandardOpenOption.CREATE_NEW);
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0) throw new IllegalStateException("shaderc options initialization failed");
        System.out.println("SHADERC START " + id);
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, TARGET);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            start = System.nanoTime();
            long result = ShadercNativeHeap.compileIntoSpv(compiler, source, Shaderc.shaderc_compute_shader,
                    name, "main", options);
            row.put("shadercNs", System.nanoTime() - start);
            if (result == 0) throw new IllegalStateException("shaderc returned null");
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success)
                    throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null) throw new IllegalStateException("No SPIR-V");
                byte[] spirv = new byte[bytes.remaining()];
                bytes.get(spirv);
                Files.write(output.resolve(id + ".spv"), spirv, StandardOpenOption.CREATE_NEW);
                row.put("spirvBytes", spirv.length);
                row.put("spirvSha256", hash(spirv));
                row.put("warnings", Shaderc.shaderc_result_get_num_warnings(result));
                row.put("structure", summarize(spirv));
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
        }
        System.out.println("SHADERC COMPLETE " + id + " " + new GsonBuilder().create().toJson(row));
        return row;
    }

    // Structural counts only, not a SPIR-V validator or a prediction of driver optimization cost.
    static Map<String, Object> summarize(byte[] bytes) {
        if (bytes.length < 20 || bytes.length % 4 != 0) throw new IllegalArgumentException("Invalid SPIR-V size");
        IntBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (words.get(0) != 0x07230203) throw new IllegalArgumentException("Invalid SPIR-V magic");
        int instructions = 0, functions = 0, calls = 0, loops = 0, selections = 0;
        for (int i = 5; i < words.limit();) {
            int header = words.get(i), count = header >>> 16, opcode = header & 65535;
            if (count == 0 || count > words.limit() - i) throw new IllegalArgumentException("Truncated SPIR-V instruction");
            instructions++;
            if (opcode == 54) functions++;
            if (opcode == 57) calls++;
            if (opcode == 246) loops++;
            if (opcode == 247) selections++;
            i += count;
        }
        return Map.of("instructions", instructions, "functions", functions, "functionCalls", calls,
                "loopMerges", loops, "selectionMerges", selections, "idBound", words.get(3));
    }

    private static Map<String, Object> exportProvenance() throws Exception {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("gitHead", git("rev-parse", "HEAD").strip());
        provenance.put("gitStatus", git("status", "--short").strip());
        provenance.put("trackedDiffSha256", hash(git("diff", "--binary", "HEAD")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // These are the actual compiled builder inputs, not merely the possibly newer Java sources.
        Map<String, Object> trees = new LinkedHashMap<>();
        for (String set : new String[]{"main", "client", "research"}) {
            Path root = Path.of("build/classes/java", set);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int count = 0;
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(root.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                    count++;
                }
            }
            trees.put(set, Map.of("sha256", HexFormat.of().formatHex(digest.digest()), "files", count));
        }
        provenance.put("compiledClassTrees", trees);
        return provenance;
    }

    private static String git(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("Cannot record export git provenance");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    static Map<String, Object> environment() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", Instant.now().toString());
        row.put("java", System.getProperty("java.runtime.version"));
        row.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        row.put("lwjgl", Version.getVersion());
        return row;
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void writeJson(Path path, Object value) throws Exception {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
