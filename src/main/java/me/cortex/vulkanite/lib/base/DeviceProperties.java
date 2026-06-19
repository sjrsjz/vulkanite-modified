package me.cortex.vulkanite.lib.base;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

public class DeviceProperties {
    //Allocates on heap to avoid stack overflow, must be freed manually
    
    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public DeviceProperties(VkDevice device) {
        // Allocate on heap to avoid stack overflow
        rtPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(rtPipelineProperties));
        }
    }
    
    public void cleanup() {
        // rtPipelineProperties is valid for the lifetime of the physical device
        // (i.e., the entire application session). Do NOT free it here — the VContext
        // persists across world loads and the cached properties are re-read each time
        // Iris creates a new pipeline after world re-entry.
    }
}
