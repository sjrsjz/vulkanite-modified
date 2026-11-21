package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

public final class DescriptorUpdateBuilder implements AutoCloseable {
    private final VContext context;
    private final MemoryStack stack = MemoryStack.stackPush();
    private final VkWriteDescriptorSet.Buffer updates;
    private long set;

    public DescriptorUpdateBuilder(VContext context, int maxUpdates) {
        this.context = context;
        try {
            updates = VkWriteDescriptorSet.calloc(maxUpdates, stack);
        } catch (RuntimeException | Error failure) {
            stack.close();
            throw failure;
        }
    }

    public DescriptorUpdateBuilder set(long set) {
        this.set = set;
        return this;
    }

    private VkWriteDescriptorSet write(int binding, int type, int count) {
        return updates.get().sType$Default().dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(count);
    }

    public DescriptorUpdateBuilder buffer(int binding, VBuffer buffer) {
        return buffer(binding, buffer.buffer(), 0, buffer.size());
    }

    public DescriptorUpdateBuilder buffer(int binding, long buffer, long offset, long range) {
        write(binding, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(offset).range(range));
        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, int firstElement, List<VBuffer> buffers) {
        var info = VkDescriptorBufferInfo.calloc(buffers.size(), stack);
        for (int i = 0; i < buffers.size(); i++) {
            var buffer = buffers.get(i);
            info.get(i).buffer(buffer.buffer()).range(buffer.size());
        }
        write(binding, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, buffers.size()).dstArrayElement(firstElement).pBufferInfo(info);
        return this;
    }

    public DescriptorUpdateBuilder uniform(int binding, VBuffer buffer) {
        write(binding, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.buffer()).range(buffer.size()));
        return this;
    }

    public DescriptorUpdateBuilder acceleration(int binding, VAccelerationStructure structure) {
        write(binding, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 1)
                .pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                        .pAccelerationStructures(stack.longs(structure.structure)));
        return this;
    }

    public DescriptorUpdateBuilder sampledImage(int binding, long view, long sampler) {
        write(binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .imageView(view).sampler(sampler));
        return this;
    }

    public DescriptorUpdateBuilder sampledImages(int binding, long[] views, long sampler) {
        var images = VkDescriptorImageInfo.calloc(views.length, stack);
        for (int i = 0; i < views.length; i++) {
            images.get(i).imageLayout(VK_IMAGE_LAYOUT_GENERAL).imageView(views[i]).sampler(sampler);
        }
        write(binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, views.length).pImageInfo(images);
        return this;
    }

    public void apply() {
        if (updates.position() != 0) vkUpdateDescriptorSets(context.device, updates.flip(), null);
    }

    @Override
    public void close() {
        stack.close();
    }
}
