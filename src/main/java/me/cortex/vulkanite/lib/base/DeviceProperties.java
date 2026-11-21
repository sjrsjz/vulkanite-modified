package me.cortex.vulkanite.lib.base;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorIndexingProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

public class DeviceProperties {
    //Allocates on heap to avoid stack overflow, must be freed manually
    
    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public final long scratchAlignment;
    public final int geometryCapacity;
    public DeviceProperties(VkDevice device) {
        // Allocate on heap to avoid stack overflow
        rtPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            var acceleration = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            var indexing = VkPhysicalDeviceDescriptorIndexingProperties.calloc(stack).sType$Default();
            rtPipelineProperties.pNext(indexing.address());
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(acceleration.pNext(rtPipelineProperties.address()).address()));
            rtPipelineProperties.pNext(0);
            scratchAlignment = acceleration.minAccelerationStructureScratchOffsetAlignment();
            // Leave room for ordinary storage-buffer bindings in the other sets.
            long perStage = Integer.toUnsignedLong(indexing.maxPerStageDescriptorUpdateAfterBindStorageBuffers());
            long perSet = Integer.toUnsignedLong(indexing.maxDescriptorSetUpdateAfterBindStorageBuffers());
            geometryCapacity = (int) Math.min(65536, Math.min(perStage, perSet) - 32);
            if (geometryCapacity < 1) {
                rtPipelineProperties.free();
                throw new IllegalStateException("Insufficient Vulkan storage descriptor capacity for ray tracing");
            }
        }
    }
    
    public void cleanup() {
        rtPipelineProperties.free();
    }
}
