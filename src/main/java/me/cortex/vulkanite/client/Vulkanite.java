package me.cortex.vulkanite.client;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.acceleration.SharedQuadVkIndexBuffer;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import me.cortex.vulkanite.client.rendering.entity.EntityTextureRegistry;
import me.cortex.vulkanite.compat.RayTracingFeatures;
import me.cortex.vulkanite.lib.base.VContext;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.irisshaders.iris.mixin.VulkanDeviceAccessor;
import net.irisshaders.iris.pipeline.GraphicsBackend;
import net.irisshaders.iris.vulkan.VulkanCommandBridge;

public final class Vulkanite {
    public static final boolean MEMORY_LEAK_TRACING = Boolean.getBoolean("vulkanite.traceResources");
    public static final Vulkanite INSTANCE = new Vulkanite();

    private VContext context;
    private AccelerationManager accelerationManager;
    private boolean closed;

    private Vulkanite() {}

    public static boolean supportsRayTracing() {
        if (!GraphicsBackend.isVulkan()) return false;
        var device = (VulkanDeviceAccessor) VulkanCommandBridge.getDevice();
        return device.iris$getEnabledFeatures().contains(RayTracingFeatures.featureSet());
    }

    public VContext getCtx() {
        if (closed) throw new IllegalStateException("Vulkanite has shut down");
        if (context == null) {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            if (!supportsRayTracing()) {
                throw new IllegalStateException("Vulkanite requires Minecraft's Vulkan backend with hardware ray tracing enabled");
            }
            var created = new VContext(VulkanCommandBridge.getDevice());
            try {
                accelerationManager = new AccelerationManager(created);
                context = created;
            } catch (RuntimeException | Error failure) {
                created.close();
                throw failure;
            }
        }
        return context;
    }

    public AccelerationManager getAccelerationManager() {
        getCtx();
        return accelerationManager;
    }

    public void upload(ChunkBuildOutput result) {
        getAccelerationManager().accept(result);
    }

    public void sectionRemove(RenderSection section) {
        if (accelerationManager != null) accelerationManager.sectionRemove(section);
    }

    public void clearWorld() {
        if (accelerationManager != null) accelerationManager.clearWorld();
        EntityTextureRegistry.INSTANCE.clear();
        EntityGeometryCollector.INSTANCE.resetHistory();
    }

    public void destroy() {
        if (closed) return;
        closed = true;
        if (context == null) return;
        accelerationManager.close();
        SharedQuadVkIndexBuffer.cleanup(context);
        context.close();
    }
}
