package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = StandardMacros.class, remap = false)
public class MixinStandardMacros {
    @Inject(method = "createStandardEnvironmentDefines", at = @At("RETURN"), cancellable = true)
    private static void injectVulkaniteDefine(CallbackInfoReturnable<com.google.common.collect.ImmutableList<StringPair>> cir) {
        if (!me.cortex.vulkanite.client.Vulkanite.supportsRayTracing()) return;
        var defines = new java.util.ArrayList<StringPair>();
        cir.getReturnValue().forEach(defines::add);
        defines.add(new StringPair("VULKANITE", "1"));
        cir.setReturnValue(com.google.common.collect.ImmutableList.copyOf(defines));
    }
}
