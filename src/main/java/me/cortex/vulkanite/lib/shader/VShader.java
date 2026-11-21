package me.cortex.vulkanite.lib.shader;

import me.cortex.vulkanite.lib.base.TrackedResourceObject;
import me.cortex.vulkanite.lib.base.VContext;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class VShader extends TrackedResourceObject {
    private final VContext ctx;
    public final long module;
    public final int stage;

    public VShader(VContext ctx, long module, int stage) {
        this.ctx = ctx;
        this.module = module;
        this.stage = stage;
    }

    public ShaderModule named() {
        return named("main");
    }

    public ShaderModule named(String name) {
        return new ShaderModule(this, name);
    }

    public static VShader compileLoad(VContext ctx, String source, int stage) {
        return compileLoad(ctx, "shader", source, stage);
    }

    public static VShader compileLoad(VContext ctx, String filename, String source, int stage) {
        ByteBuffer code = ShaderCompiler.compileShader(filename, source, stage);
        try (var stack = stackPush()) {

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pShaderModule = stack.mallocLong(1);
            _CHECK_(vkCreateShaderModule(ctx.device, createInfo, null, pShaderModule));
            return new VShader(ctx, pShaderModule.get(0), stage);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(code);
        }
    }

    @Override
    public void free() {
        free0();
        vkDestroyShaderModule(ctx.device, module, null);
    }
}
