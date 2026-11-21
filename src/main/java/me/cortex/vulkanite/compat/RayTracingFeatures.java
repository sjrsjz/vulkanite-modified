package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;

import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;

public final class RayTracingFeatures {
    private RayTracingFeatures() {}

    public static FeatureSet featureSet() {
        var vk10 = VulkanFeatureSets.VK10_FEATURES_STRUCT;
        var vk11 = VulkanFeatureSets.VK11_FEATURES_STRUCT;
        var vk12 = VulkanFeatureSets.VK12_FEATURES_STRUCT;
        var acceleration = new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
        var rayTracing = new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class);
        return new FeatureSet("Vulkanite ray tracing",
                Set.of(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                        VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                        VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME),
                Set.of(new VulkanFeature(acceleration, "accelerationStructure"),
                        new VulkanFeature(rayTracing, "rayTracingPipeline"),
                        new VulkanFeature(vk10, "shaderInt16"),
                        new VulkanFeature(vk10, "shaderInt64"),
                        new VulkanFeature(vk11, "storageBuffer16BitAccess"),
                        new VulkanFeature(vk12, "bufferDeviceAddress"),
                        new VulkanFeature(vk12, "descriptorIndexing"),
                        new VulkanFeature(vk12, "descriptorBindingPartiallyBound"),
                        new VulkanFeature(vk12, "descriptorBindingStorageBufferUpdateAfterBind"),
                        new VulkanFeature(vk12, "descriptorBindingUpdateUnusedWhilePending"),
                        new VulkanFeature(vk12, "descriptorBindingVariableDescriptorCount"),
                        new VulkanFeature(vk12, "runtimeDescriptorArray"),
                        new VulkanFeature(vk12, "scalarBlockLayout"),
                        new VulkanFeature(vk12, "shaderFloat16"),
                        new VulkanFeature(vk12, "shaderInt8"),
                        new VulkanFeature(vk12, "shaderSampledImageArrayNonUniformIndexing"),
                        new VulkanFeature(vk12, "shaderStorageBufferArrayNonUniformIndexing"),
                        new VulkanFeature(vk12, "storageBuffer8BitAccess")));
    }
}
