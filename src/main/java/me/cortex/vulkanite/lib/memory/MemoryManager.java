package me.cortex.vulkanite.lib.memory;

import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_AUTO;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.VK10.*;

public final class MemoryManager {
    private final VkDevice device;
    private final VmaAllocator allocator;

    public MemoryManager(VkDevice device) {
        this.device = device;
        // RenderPearl's own allocator does not allocate device-address memory.
        this.allocator = new VmaAllocator(device);
    }

    public VBuffer createBuffer(long size, int usage, int properties) {
        return createBuffer(size, usage, properties, 0, 0);
    }

    public VBuffer createBuffer(long size, int usage, int properties, long alignment, int flags) {
        try (var stack = stackPush()) {
            var info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var memory = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                    .requiredFlags(properties).flags(flags);
            return new VBuffer(allocator.allocate(info, memory, alignment));
        }
    }

    public VImage createImage2D(int width, int height, int levels, int format, int usage, int properties) {
        try (var stack = stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(format).mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            info.extent().set(width, height, 1);
            var memory = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO).requiredFlags(properties);
            return new VImage(allocator.allocate(info, memory), width, height, 1, levels, format, 2);
        }
    }

    public VAccelerationStructure createAcceleration(long size, int alignment, int usage, int type) {
        var buffer = createBuffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, alignment, 0);
        try (var stack = stackPush()) {
            var result = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default().buffer(buffer.buffer()).size(size).type(type), null, result),
                    "Create acceleration structure");
            return new VAccelerationStructure(device, result.get(0), buffer);
        } catch (RuntimeException | Error failure) {
            buffer.free();
            throw failure;
        }
    }

    public void cleanup() {
        allocator.cleanup();
    }
}
