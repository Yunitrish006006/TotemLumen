package dev.totem.lumen.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * Calls shaderc with UTF-8 buffers allocated on the native heap instead of LWJGL's MemoryStack.
 *
 * <p>The CharSequence convenience overload in LWJGL temporarily UTF-8 encodes the complete GLSL
 * source on {@code MemoryStack}. Totem Lumen's transformed production shader is large enough to
 * cross that stack budget, so both runtime prewarm and CI verification use this bounded-lifetime
 * native allocation path.</p>
 */
public final class ShadercNativeHeap {
    private ShadercNativeHeap() {
    }

    public static long compileIntoSpv(
            long compiler,
            CharSequence sourceText,
            int shaderKind,
            CharSequence inputFileName,
            CharSequence entryPointName,
            long options
    ) {
        ByteBuffer source = MemoryUtil.memUTF8(sourceText, false);
        ByteBuffer fileName = MemoryUtil.memUTF8(inputFileName, true);
        ByteBuffer entryPoint = MemoryUtil.memUTF8(entryPointName, true);
        try {
            return Shaderc.shaderc_compile_into_spv(
                    compiler,
                    source,
                    shaderKind,
                    fileName,
                    entryPoint,
                    options
            );
        } finally {
            MemoryUtil.memFree(entryPoint);
            MemoryUtil.memFree(fileName);
            MemoryUtil.memFree(source);
        }
    }
}
