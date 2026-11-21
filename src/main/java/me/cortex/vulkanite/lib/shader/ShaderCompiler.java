package me.cortex.vulkanite.lib.shader;

import net.irisshaders.iris.gl.shader.ShaderCompileException;
import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class ShaderCompiler {
    private static int vulkanStageToShadercKind(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return shaderc_vertex_shader;
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return shaderc_fragment_shader;
            case VK_SHADER_STAGE_RAYGEN_BIT_KHR:
                return shaderc_raygen_shader;
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR:
                return shaderc_closesthit_shader;
            case VK_SHADER_STAGE_MISS_BIT_KHR:
                return shaderc_miss_shader;
            case VK_SHADER_STAGE_ANY_HIT_BIT_KHR:
                return shaderc_anyhit_shader;
            case VK_SHADER_STAGE_INTERSECTION_BIT_KHR:
                return shaderc_intersection_shader;
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return shaderc_compute_shader;
            default:
                throw new IllegalArgumentException("Stage: " + stage);
        }
    }

    public static ByteBuffer compileShader(String filename, String source, int vulkanStage) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new ShaderCompileException(filename, "Failed to create shader compiler");
        }
        long options = 0L;
        long result = 0L;
        ByteBuffer sourceBytes = null;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0L) {
                throw new ShaderCompileException(filename, "Failed to create shader compiler options");
            }
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

            // Expanded shader packs can be megabytes long. LWJGL's String overload
            // encodes the whole source on its small thread-local native stack.
            sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                result = shaderc_compile_into_spv(compiler, sourceBytes, vulkanStageToShadercKind(vulkanStage),
                        stack.UTF8(filename), stack.UTF8("main"), options);
            }
            if (result == 0L) {
                throw new ShaderCompileException(filename, "shaderc returned no compilation result");
            }
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new ShaderCompileException(filename, shaderc_result_get_error_message(result));
            }

            ByteBuffer code = shaderc_result_get_bytes(result);
            ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(code.remaining());
            copy.put(code).flip();
            return copy;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            if (result != 0L) {
                shaderc_result_release(result);
            }
            if (options != 0L) {
                shaderc_compile_options_release(options);
            }
            shaderc_compiler_release(compiler);
        }
    }
}
