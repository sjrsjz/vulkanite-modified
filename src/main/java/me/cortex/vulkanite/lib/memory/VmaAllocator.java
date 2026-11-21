package me.cortex.vulkanite.lib.memory;

import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK12.*;

public final class VmaAllocator {
    private final VkDevice device;
    private final long allocator;

    VmaAllocator(VkDevice device) {
        this.device = device;
        var physical = device.getPhysicalDevice();
        var instance = physical.getInstance();
        try (var stack = stackPush()) {
            var info = VmaAllocatorCreateInfo.calloc(stack).device(device).physicalDevice(physical)
                    .instance(instance).vulkanApiVersion(VK_API_VERSION_1_2)
                    .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(instance, device))
                    .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
            var result = stack.mallocPointer(1);
            _CHECK_(vmaCreateAllocator(info, result), "Create the Vulkanite allocator");
            allocator = result.get(0);
        }
    }

    void cleanup() {
        vmaDestroyAllocator(allocator);
    }

    BufferAllocation allocate(VkBufferCreateInfo bufferInfo, VmaAllocationCreateInfo memoryInfo, long alignment) {
        try (var stack = stackPush()) {
            var buffer = stack.mallocLong(1);
            var memory = stack.mallocPointer(1);
            int result = vmaCreateBuffer(allocator, bufferInfo, memoryInfo.minAlignment(alignment), buffer, memory, null);
            _CHECK_(result, "Allocate Vulkanite buffer (" + bufferInfo.size() + " bytes)");
            long address = 0;
            if ((bufferInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                address = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack)
                        .sType$Default().buffer(buffer.get(0)));
                if (address == 0) {
                    vmaDestroyBuffer(allocator, buffer.get(0), memory.get(0));
                    throw new IllegalStateException("Vulkanite buffer has no device address");
                }
            }
            return new BufferAllocation(buffer.get(0), memory.get(0), address, bufferInfo.size());
        }
    }

    ImageAllocation allocate(VkImageCreateInfo imageInfo, VmaAllocationCreateInfo memoryInfo) {
        try (var stack = stackPush()) {
            var image = stack.mallocLong(1);
            var memory = stack.mallocPointer(1);
            _CHECK_(vmaCreateImage(allocator, imageInfo, memoryInfo, image, memory, null), "Allocate Vulkanite image");
            return new ImageAllocation(image.get(0), memory.get(0));
        }
    }

    final class BufferAllocation {
        final long buffer;
        final long memory;
        final long deviceAddress;
        private final long size;

        BufferAllocation(long buffer, long memory, long deviceAddress, long size) {
            this.buffer = buffer;
            this.memory = memory;
            this.deviceAddress = deviceAddress;
            this.size = size;
        }

        long size() {
            return size;
        }

        long map() {
            try (var stack = stackPush()) {
                var address = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(allocator, memory, address), "Map Vulkanite buffer");
                return address.get(0);
            }
        }

        void unmap() {
            vmaUnmapMemory(allocator, memory);
        }

        void flush(long offset, long size) {
            vmaFlushAllocation(allocator, memory, offset, size);
        }

        void free() {
            vmaDestroyBuffer(allocator, buffer, memory);
        }
    }

    final class ImageAllocation {
        final long image;
        private final long memory;

        ImageAllocation(long image, long memory) {
            this.image = image;
            this.memory = memory;
        }

        void free() {
            vmaDestroyImage(allocator, image, memory);
        }

        VkDevice getDevice() {
            return device;
        }
    }
}
