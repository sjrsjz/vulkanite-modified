package me.cortex.vulkanite.lib.cmd;

import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkDependencyInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK10.*;
import me.cortex.vulkanite.lib.base.VContext;

/** Records work into the active RenderPearl command buffer. */
public record VCmdBuff(VkCommandBuffer buffer) {
    public void upload(VContext context, long source, VBuffer destination, long size) {
        var staging = context.memory.createBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        context.retire(staging::free);
        long mapped = staging.map();
        try {
            MemoryUtil.memCopy(source, mapped, size);
            staging.flush();
        } finally {
            staging.unmap();
        }
        try (var stack = stackPush()) {
            vkCmdCopyBuffer(buffer, staging.buffer(), destination.buffer(), VkBufferCopy.calloc(1, stack).size(size));
        }
    }

    public void barrier(long sourceStage, long sourceAccess, long destinationStage, long destinationAccess) {
        try (var stack = stackPush()) {
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
            vkCmdPipelineBarrier2KHR(buffer, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }
}
