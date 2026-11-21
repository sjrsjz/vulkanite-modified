package me.cortex.vulkanite.lib.base;

import me.cortex.vulkanite.lib.cmd.CommandManager;
import me.cortex.vulkanite.lib.other.sync.SyncManager;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import org.lwjgl.vulkan.VkDevice;
import static org.lwjgl.vulkan.VK10.*;

public class VContext {
    public final VkDevice device;

    public final int queueCount;
    public final int queueFamily;

    public final MemoryManager memory;
    public final SyncManager sync;
    public final CommandManager cmd;
    public final DeviceProperties properties;
    public VContext(VkDevice device, int queueCount, int queueFamily, boolean hasDeviceAddresses) {
        this.device = device;
        this.queueCount = queueCount;
        this.queueFamily = queueFamily;
        memory = new MemoryManager(device, hasDeviceAddresses);
        sync = new SyncManager(device);
        cmd = new CommandManager(device, queueCount, queueFamily, sync);
        properties = new DeviceProperties(device);
    }

    public void cleanup() {
        properties.cleanup();
        memory.cleanup();
        vkDestroyDevice(device, null);
        vkDestroyInstance(device.getPhysicalDevice().getInstance(), null);
    }
}
