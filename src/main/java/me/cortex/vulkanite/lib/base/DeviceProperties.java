package me.cortex.vulkanite.lib.base;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

public class DeviceProperties {
    //Allocates on heap to avoid stack overflow, must be freed manually
    
    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public final long scratchAlignment;
    public DeviceProperties(VkDevice device) {
        // Allocate on heap to avoid stack overflow
        rtPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            var acceleration = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(acceleration.pNext(rtPipelineProperties.address())));
            scratchAlignment = acceleration.minAccelerationStructureScratchOffsetAlignment();
        }
    }
    
    public void cleanup() {
        rtPipelineProperties.free();
    }
}
