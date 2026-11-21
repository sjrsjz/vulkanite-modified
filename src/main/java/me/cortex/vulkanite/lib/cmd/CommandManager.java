package me.cortex.vulkanite.lib.cmd;

import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.other.sync.SyncManager;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Manages multiple command queues and fence synchronizations
public class CommandManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/CommandManager");
    private final VkDevice device;
    private final VkQueue[] queues;
    private volatile int submitCount;
    private final int queueFamily;
    private final SyncManager sync;

    public CommandManager(VkDevice device, int queues, int queueFamily, SyncManager sync) {
        this.device = device;
        this.queueFamily = queueFamily;
        this.sync = sync;
        this.queues = new VkQueue[queues];
        try (var stack = stackPush()) {
            var pQ = stack.pointers(0);
            for (int i = 0; i < queues; i++) {
                vkGetDeviceQueue(device, queueFamily, i, pQ);
                LOGGER.debug("Queue {} has address {}", i, Long.toHexString(pQ.get(0)));
                this.queues[i] = new VkQueue(pQ.get(0), device);
            }
        }
        LOGGER.info("CommandManager initialized with {} queues", queues);
    }

    public VCommandPool createSingleUsePool() {
        return createPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
    }

    public VCommandPool createPool(int flags) {
        return new VCommandPool(device, queueFamily, flags);
    }

    public void submit(int queueId, VkSubmitInfo submit) {
        synchronized (submitLock) {
            sync.checkResult(vkQueueSubmit(queues[queueId], submit, 0), "vkQueueSubmit");
        }
    }

    public void submitOnceAndWait(int queueId, VCmdBuff cmdBuff) {
        try (var stack = stackPush()) {
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmdBuff))
                    .pWaitSemaphores(stack.longs())
                    .pWaitDstStageMask(stack.ints())
                    .pSignalSemaphores(stack.longs());
            synchronized (submitLock) {
                sync.checkResult(vkQueueSubmit(queues[queueId], submit, 0), "vkQueueSubmit");
                sync.checkResult(vkQueueWaitIdle(queues[queueId]), "vkQueueWaitIdle");
            }
            cmdBuff.releaseNow();
        }
    }

    //TODO: if its a single use command buffer, automatically add the required fences and stuff to free the command buffer once its done
    private final Object submitLock = new Object();

    public void submit(int queueId, VCmdBuff[] cmdBuffs, VSemaphore[] waits, int[] waitStages, VSemaphore[] triggers, VFence fence) {
        int id = ++submitCount;
        synchronized (submitLock) {
        try (var stack = stackPush()) {
            LongBuffer waitSemaphores = stack.mallocLong(waits.length);
            LongBuffer signalSemaphores = stack.mallocLong(triggers.length);
            for (var wait : waits) {waitSemaphores.put(wait.address());}
            for (var trigger : triggers) {signalSemaphores.put(trigger.address());}
            waitSemaphores.rewind();
            signalSemaphores.rewind();
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmdBuffs))
                    .pWaitSemaphores(waitSemaphores)
                    .waitSemaphoreCount(waits.length)
                    .pWaitDstStageMask(stack.ints(waitStages))
                    .pSignalSemaphores(signalSemaphores);
            if (sync.isDeviceLost()) throw new IllegalStateException("Vulkan context has failed");
            sync.checkResult(vkQueueSubmit(queues[queueId], submit, fence==null?0:fence.address()), "vkQueueSubmit");
        }
        }
    }

    public void waitQueueIdle(int queue) {
        synchronized (submitLock) {
            int result = vkQueueWaitIdle(queues[Math.min(queue, queues.length - 1)]);
            if (result != VK_SUCCESS) sync.fail("vkQueueWaitIdle: " + VUtil.translateVulkanResult(result), null);
        }
    }

    public void waitIdle() {
        synchronized (submitLock) {
            int result = vkDeviceWaitIdle(device);
            if (result != VK_SUCCESS) sync.fail("vkDeviceWaitIdle: " + VUtil.translateVulkanResult(result), null);
        }
    }
}
