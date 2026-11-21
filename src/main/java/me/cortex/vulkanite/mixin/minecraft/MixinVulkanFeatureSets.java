package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import me.cortex.vulkanite.compat.RayTracingFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(VulkanFeatureSets.class)
public class MixinVulkanFeatureSets {
    @Unique
    private static FeatureSet vulkanite$rayTracingFeatures;

    @Inject(method = "optionalFeatureSets", at = @At("RETURN"))
    private static void vulkanite$enableRayTracing(CallbackInfoReturnable<Set<FeatureSet>> ci) {
        if (vulkanite$rayTracingFeatures == null) {
            vulkanite$rayTracingFeatures = RayTracingFeatures.featureSet();
        }
        ci.getReturnValue().add(vulkanite$rayTracingFeatures);
    }
}
