package me.cortex.vulkanite.lib.cmd;

import me.cortex.vulkanite.lib.base.TrackedResourceObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VCommandPool extends TrackedResourceObject {
    final VkDevice device;
    final long pool;
    private final java.util.Set<VCmdBuff> allocated = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean closed;
    public VCommandPool(VkDevice device, int flags) {
        this(device, 0, flags);
    }

    public VCommandPool(VkDevice device, int family, int flags) {
        this.device = device;
        try (var stack = stackPush()) {
            VkCommandPoolCreateInfo cmdPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queueFamilyIndex(family)
                    .flags(flags);
            LongBuffer pCmdPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, cmdPoolInfo, null, pCmdPool));
            pool = pCmdPool.get(0);
        }
    }

    public synchronized VCmdBuff createCommandBuffer() {
        return createCommandBuffers(1)[0];
    }

    public synchronized VCmdBuff[] createCommandBuffers(int count) {
        return createCommandBuffers(count, 0);
    }

    public synchronized VCmdBuff[] createCommandBuffers(int count, int level) {
        if (closed) throw new IllegalStateException("Command pool is closed");
        try (MemoryStack stack = MemoryStack.stackPush()){
            PointerBuffer pCommandBuffer = stack.mallocPointer(count);
            _CHECK_(vkAllocateCommandBuffers(device,
                            VkCommandBufferAllocateInfo
                                    .calloc(stack)
                                    .sType$Default()
                                    .commandPool(pool)
                                    .level(level)
                                    .commandBufferCount(count), pCommandBuffer),
                    "Failed to create command buffer");
            VCmdBuff[] buffers = new VCmdBuff[count];
            for (int i = 0; i < count; i++) {
                buffers[i] = new VCmdBuff(this, new VkCommandBuffer(pCommandBuffer.get(i), device));
                allocated.add(buffers[i]);
            }
            return buffers;
        }
    }

    private final ConcurrentLinkedDeque<VCmdBuff> toRelease = new ConcurrentLinkedDeque<>();
    void free(VCmdBuff cmdBuff) {
        if (!closed) toRelease.add(cmdBuff);
    }

    public void doReleases() {
        while (!toRelease.isEmpty()) {
            releaseNow(toRelease.poll());
        }
    }

    public synchronized void releaseNow(VCmdBuff cmd) {
        if (allocated.remove(cmd)) cmd.freeInternal();
    }

    @Override
    public synchronized void free() {
        if (closed) return;
        closed = true;
        for (var cmd : allocated) cmd.freeInternal();
        allocated.clear();
        toRelease.clear();
        free0();
        vkDestroyCommandPool(device, pool, null);
    }
}
