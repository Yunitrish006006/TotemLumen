package dev.totem.lumen.vulkan;

import dev.totem.lumen.TotemLumenClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persistent cache for generated SPIR-V.
 *
 * <p>Large Totem shaders are assembled from Java shader patches at runtime. The resulting GLSL is
 * deterministic for one mod build, so hashing the final source plus compiler settings lets later
 * launches skip shaderc completely while still invalidating automatically when any patch changes.</p>
 */
final class ShaderSpirvCache {
    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final int TARGET_ENV_VERSION = 4_202_496;
    private static final int SPIRV_MAGIC = 0x07230203;

    private ShaderSpirvCache() {
    }

    static byte[] load(String shaderName, String source, int optimizationLevel) {
        Path path = cachePath(shaderName, source, optimizationLevel);
        if (!Files.isRegularFile(path)) {
            TotemLumenClient.LOGGER.info(
                    "SPIR-V cache MISS: shader={}, file={}",
                    shaderName,
                    path.getFileName()
            );
            return null;
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!valid(bytes)) {
                TotemLumenClient.LOGGER.warn(
                        "Ignoring invalid SPIR-V cache entry for {}: {}",
                        shaderName,
                        path.getFileName()
                );
                Files.deleteIfExists(path);
                return null;
            }
            TotemLumenClient.LOGGER.info(
                    "SPIR-V cache HIT: shader={}, file={}, bytes={}",
                    shaderName,
                    path.getFileName(),
                    bytes.length
            );
            return bytes;
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to read SPIR-V cache for {}; recompiling",
                    shaderName,
                    failure
            );
            return null;
        }
    }

    static void store(String shaderName, String source, int optimizationLevel, byte[] spirv) {
        if (!valid(spirv)) {
            return;
        }

        Path path = cachePath(shaderName, source, optimizationLevel);
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(
                    temporary,
                    spirv,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            TotemLumenClient.LOGGER.info(
                    "SPIR-V cache SAVED: shader={}, file={}, bytes={}",
                    shaderName,
                    path.getFileName(),
                    spirv.length
            );
        } catch (IOException failure) {
            TotemLumenClient.LOGGER.warn(
                    "Failed to persist SPIR-V cache after {}; renderer remains usable",
                    shaderName,
                    failure
            );
        }
    }

    private static Path cachePath(String shaderName, String source, int optimizationLevel) {
        String hash = digest(shaderName, source, optimizationLevel);
        return FabricLoader.getInstance()
                .getGameDir()
                .resolve("cache")
                .resolve("totem-lumen")
                .resolve("spirv")
                .resolve("v" + CACHE_SCHEMA_VERSION)
                .resolve(hash + ".spv");
    }

    private static String digest(String shaderName, String source, int optimizationLevel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("schema=" + CACHE_SCHEMA_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(("target=" + TARGET_ENV_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(("optimization=" + optimizationLevel + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(("name=" + shaderName + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean valid(byte[] bytes) {
        if (bytes == null || bytes.length < 20 || (bytes.length & 3) != 0) {
            return false;
        }
        int magic = (bytes[0] & 0xff)
                | ((bytes[1] & 0xff) << 8)
                | ((bytes[2] & 0xff) << 16)
                | ((bytes[3] & 0xff) << 24);
        return magic == SPIRV_MAGIC;
    }
}
