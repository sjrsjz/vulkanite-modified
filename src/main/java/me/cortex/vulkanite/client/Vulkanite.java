package me.cortex.vulkanite.client;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.initalizer.VInitializer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceCapabilities.VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceFd.VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceWin32.VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryCapabilities.VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreCapabilities.VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME;

public class Vulkanite {
    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public static boolean MEMORY_LEAK_TRACING = Boolean.getBoolean("vulkanite.traceResources");

    public static boolean IS_ENABLED = true;
    public static final Vulkanite INSTANCE = new Vulkanite();

    private VContext ctx;
    private Throwable initializationFailure;
    private boolean destroyed;
    private final ArbitarySyncPointCallback fencedCallback = new ArbitarySyncPointCallback();

    private AccelerationManager accelerationManager;

    public Vulkanite() {
        // Loading mixins or rendering a normal Iris pack must not create a device.
    }

    public void upload(List<ChunkBuildOutput> results) {
        if (!me.cortex.vulkanite.compat.RaytracingPackState.isActive())
            return;
        getAccelerationManager().chunkBuilds(results);
    }

    public void sectionRemove(RenderSection section) {
        if (accelerationManager != null)
            accelerationManager.sectionRemove(section);
    }

    public void renderTick() {
        if (ctx == null || destroyed) return;
        ctx.sync.checkFences();
        if (accelerationManager != null)
            accelerationManager.updateTick();
    }

    public void fenceTick() {
        fencedCallback.tick();
    }

    public VContext getCtx() {
        if (destroyed) throw new IllegalStateException("Vulkanite has been shut down");
        if (initializationFailure != null) throw new IllegalStateException("Vulkan initialization previously failed", initializationFailure);
        if (ctx == null) {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            try {
                ctx = createVulkanContext();
                accelerationManager = new AccelerationManager(ctx, Math.min(1, ctx.queueCount - 1));
            } catch (RuntimeException | LinkageError | AssertionError e) {
                initializationFailure = e;
                throw e;
            }
        }
        return ctx;
    }

    public void addSyncedCallback(Runnable callback) {
        fencedCallback.enqueue(callback);
    }

    public void destroy() {
        if (destroyed) return;
        if (ctx == null) { destroyed = true; return; }
        net.irisshaders.iris.Iris.getPipelineManager().destroyPipeline();
        if (accelerationManager != null)
            accelerationManager.cleanup();
        ctx.cmd.waitIdle();
        ctx.sync.drainCallbacks();
        fencedCallback.drain();
        me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer.cleanup();
        ctx.cleanup();
        destroyed = true;
    }

    public void clearWorld() {
        if (accelerationManager != null && !destroyed) accelerationManager.clearWorld();
        me.cortex.vulkanite.client.rendering.entity.EntityTextureRegistry.INSTANCE.clear();
        me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector.INSTANCE.resetHistory();
    }

    private static VContext createVulkanContext() {
        var init = new VInitializer("Vulkan test", "Vulkanite", 1, 3,
                new String[] { VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME,
                // VK_EXT_DEBUG_UTILS_EXTENSION_NAME
                },
                new String[] {
                // "VK_LAYER_KHRONOS_validation",
                });

        // This copies whatever gpu the opengl context is on
        try {
        init.findPhysicalDevice();

        List<String> extensions = new ArrayList<>(List.of(
                VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                VK_KHR_SPIRV_1_4_EXTENSION_NAME,
                VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME,

                // VK_KHR_RAY_QUERY_EXTENSION_NAME,

                VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,

                VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));
        if (IS_WINDOWS) {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME));
        } else {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME));
        }
        init.createDevice(extensions,
                List.of(),
                new float[] { 1.0f, 0.9f },
                features -> features.shaderInt16(true).shaderInt64(true).multiDrawIndirect(true), List.of(
                        stack -> VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                                .sType$Default(),

                        stack -> VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                                .sType$Default(),

                        stack -> VkPhysicalDeviceVulkan11Features.calloc(stack)
                                .sType$Default(),

                        stack -> VkPhysicalDeviceVulkan12Features.calloc(stack)
                                .sType$Default()));

        return init.createContext();
        } catch (RuntimeException | AssertionError e) {
            init.abort();
            throw e;
        }
    }

    public AccelerationManager getAccelerationManager() {
        getCtx();
        return accelerationManager;
    }

}
