package me.cortex.vulkanite.lib.base.initalizer;

import me.cortex.vulkanite.lib.base.VContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

public class VInitializer {
    private final VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private int queueCount;
    private int queueFamily = -1;
    private Logger LOGGER = LoggerFactory.getLogger("Vulkanite/VInitializer");

    public VInitializer(String appName, String engineName, int major, int minor, String[] extensions, String[] layers) {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .apiVersion(VK_MAKE_VERSION(major, minor, 0))
                    .pApplicationName(stack.UTF8(appName))
                    .pEngineName(stack.UTF8(engineName));

            VkInstanceCreateInfo instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(
                            stack.pointers(Arrays.stream(extensions).map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .ppEnabledLayerNames(
                            stack.pointers(Arrays.stream(layers).map(stack::UTF8).toArray(ByteBuffer[]::new)));

            PointerBuffer result = stack.pointers(0);
            _CHECK_(vkCreateInstance(instanceCreateInfo, null, result));

            instance = new VkInstance(result.get(0), instanceCreateInfo);
        }
    }

    public void findPhysicalDevice() {
        String glRenderer = GL11C.glGetString(GL11C.GL_RENDERER);
        LOGGER.info("OpenGL Renderer: " + glRenderer);
        var gl = org.lwjgl.opengl.GL.getCapabilities();
        if (!gl.GL_EXT_memory_object || !gl.GL_EXT_semaphore
                || (me.cortex.vulkanite.client.Vulkanite.IS_WINDOWS
                ? !gl.GL_EXT_memory_object_win32 || !gl.GL_EXT_semaphore_win32
                : !gl.GL_EXT_memory_object_fd || !gl.GL_EXT_semaphore_fd)) {
            throw new IllegalStateException("OpenGL external memory/semaphore sharing is unavailable");
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer devices = getPhysicalDevices(stack);
            var glUuid = stack.malloc(16);
            int glDeviceCount = GL11C.glGetInteger(org.lwjgl.opengl.EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
            for (int i = 0; i < devices.capacity(); i++) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                String deviceName = props.deviceNameString();
                LOGGER.info("Vulkan Device " + i + ": " + deviceName);
                var ids = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(candidate,
                        VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ids));
                for (int g = 0; g < glDeviceCount; g++) {
                    org.lwjgl.opengl.EXTMemoryObject.glGetUnsignedBytei_vEXT(
                            org.lwjgl.opengl.EXTMemoryObject.GL_DEVICE_UUID_EXT, g, glUuid);
                    if (glUuid.equals(ids.deviceUUID())) {
                        physicalDevice = candidate;
                        LOGGER.info("Matched OpenGL/Vulkan device UUID: {}", deviceName);
                        return;
                    }
                }
            }
            throw new IllegalStateException("No Vulkan device matches the OpenGL device UUID (" + glRenderer + ")");
        }
    }

    // TODO: add nice queue creation system
    public void createDevice(List<String> extensions, List<String> layers, float[] queuePriorities,
            Consumer<VkPhysicalDeviceFeatures> deviceFeatures, List<Function<MemoryStack, Struct>> applicators) {
        var deviceExtensions = new HashSet<>(getDeviceExtensionStrings(physicalDevice));
        for (var extension : extensions) {
            if (!deviceExtensions.contains(extension)) {
                throw new IllegalStateException("Physical device is missing extension: " + extension);
            }
        }

        try (MemoryStack stack = stackPush()) {

            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
            for (int i = 0; i < families.capacity(); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && families.get(i).queueCount() > 0) {
                    queueFamily = i;
                    queueCount = Math.min(queuePriorities.length, families.get(i).queueCount());
                    break;
                }
            }
            if (queueFamily < 0) throw new IllegalStateException("No compute-capable Vulkan queue family");
            var queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pQueuePriorities(stack.floats(Arrays.copyOf(queuePriorities, queueCount)))
                    .queueFamilyIndex(queueFamily);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .ppEnabledExtensionNames(
                            stack.pointers(extensions.stream().map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .ppEnabledLayerNames(stack.pointers(layers.stream().map(stack::UTF8).toArray(ByteBuffer[]::new)))
                    .pQueueCreateInfos(queueCreateInfos);

            if (deviceFeatures != null) {
                var features = VkPhysicalDeviceFeatures.calloc(stack);
                deviceFeatures.accept(features);
                createInfo.pEnabledFeatures(features);
            } else {
                createInfo.pEnabledFeatures(null);
            }

            long chain = createInfo.address();
            var deviceProperties2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (var applicator : applicators) {
                Struct feature = applicator.apply(stack);
                deviceProperties2.pNext(feature.address());
                vkGetPhysicalDeviceFeatures2(physicalDevice, deviceProperties2);
                long next = feature.address();
                MemoryUtil.memPutAddress(chain + 8, next);
                chain = next;
            }

            PointerBuffer pDevice = stack.callocPointer(1);
            _CHECK_(vkCreateDevice(physicalDevice, createInfo, null, pDevice));
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);
        }
    }

    private PointerBuffer getPhysicalDevices(MemoryStack stack) {
        int[] res = new int[1];
        _CHECK_(vkEnumeratePhysicalDevices(instance, res, null));
        // Allocate on heap to avoid stack overflow
        PointerBuffer devices = org.lwjgl.system.MemoryUtil.memCallocPointer(res[0]);
        try {
            _CHECK_(vkEnumeratePhysicalDevices(instance, res, devices));
            if (res[0] != devices.capacity())
                throw new IllegalStateException();
            // Copy to stack-allocated buffer
            PointerBuffer stackBuffer = stack.callocPointer(res[0]);
            devices.rewind();
            stackBuffer.put(devices);
            stackBuffer.rewind();
            return stackBuffer;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(devices);
        }
    }

    private List<String> getDeviceExtensionStrings(VkPhysicalDevice device) {
        List<String> extensions = new ArrayList<>();
        try (var stack = stackPush()) {
            var count = stack.ints(0);
            _CHECK_(vkEnumerateDeviceExtensionProperties(device, (String) null, count, null));
            // A desktop driver can expose hundreds of entries (>64 KiB). Never
            // copy this buffer back onto LWJGL's small thread-local stack.
            try (var properties = VkExtensionProperties.calloc(count.get(0))) {
                _CHECK_(vkEnumerateDeviceExtensionProperties(device, (String) null, count, properties));
                for (var extension : properties) extensions.add(extension.extensionNameString());
            }
        }
        return extensions;
    }

    public VContext createContext() {
        // TODO:FIXME: DONT HARDCODE THE FACT IT HAS DEVICE ADDRESSES
        return new VContext(device, queueCount, queueFamily, true);
    }

    public void abort() {
        if (device != null) vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }
}
