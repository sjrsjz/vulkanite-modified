package me.cortex.vulkanite.lib.base;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import net.irisshaders.iris.vulkan.VulkanCommandBridge;
import org.lwjgl.vulkan.VkDevice;

public final class VContext implements AutoCloseable {
    public final VkDevice device;
    public final MemoryManager memory;
    public final DeviceProperties properties;

    public VContext(VulkanDevice gameDevice) {
        device = gameDevice.vkDevice();
        properties = new DeviceProperties(device);
        try {
            memory = new MemoryManager(device);
        } catch (RuntimeException | Error failure) {
            properties.cleanup();
            throw failure;
        }
    }

    public VCmdBuff commands() {
        return new VCmdBuff(VulkanCommandBridge.commandBuffer());
    }

    public void retire(Runnable destructor) {
        VulkanCommandBridge.deleteWhenDone(destructor);
    }

    @Override
    public void close() {
        properties.cleanup();
        retire(memory::cleanup);
    }
}
