package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.RaytracingPackState;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/Iris");
    @Shadow @Final
    private CustomTextureManager customTextureManager;

    @Unique
    private VulkanPipeline vulkanite$pipeline;
    @Unique
    private int vulkanite$width;
    @Unique
    private int vulkanite$height;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkanite$createPipeline(ProgramSet set, CallbackInfo ci) {
        var rayTracing = (IGetRaytracingSource) set;
        var sources = rayTracing.getRaytracingSource();
        if (sources.length == 0) return;

        var passes = new RaytracingShaderSet[sources.length];
        try {
            var context = Vulkanite.INSTANCE.getCtx();
            for (int i = 0; i < sources.length; i++) passes[i] = new RaytracingShaderSet(context, sources[i]);
            var textures = new ArrayList<Map.Entry<String, TextureAccess>>(customTextureManager.getIrisCustomTextures().entrySet());
            textures.addAll(customTextureManager.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW).entrySet());
            textures.sort(Comparator.comparing(Map.Entry::getKey));
            var bindings = set.getPack().getBufferObjects().keySet().toIntArray();
            vulkanite$pipeline = new VulkanPipeline(context, Vulkanite.INSTANCE.getAccelerationManager(),
                    passes, bindings, textures.stream().map(Map.Entry::getValue).toList(), rayTracing.usesTerrainLightBvh());
            EntityGeometryCollector.INSTANCE.setRayTracingActive(true);
            RaytracingPackState.setActive(true);
            LOGGER.info("Vulkanite native Vulkan pipeline initialized with {} ray passes and {} Iris SSBOs",
                    passes.length, bindings.length);
        } catch (RuntimeException | AssertionError failure) {
            // Iris's constructor reached its tail with a complete raster pipeline. Release it before Iris falls back.
            try {
                ((IrisRenderingPipeline) (Object) this).destroy();
            } catch (RuntimeException | AssertionError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Could not initialize the Vulkanite ray tracing pipeline", failure);
        } finally {
            for (var pass : passes) if (pass != null) pass.delete();
        }
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void vulkanite$trace(LevelRendererAccessor renderer, Camera camera, CameraRenderState state, CallbackInfo ci) {
        if (vulkanite$pipeline == null) return;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // Let the pack's composite passes establish frame data on startup and after a resize.
        if (target.width != vulkanite$width || target.height != vulkanite$height) {
            vulkanite$width = target.width;
            vulkanite$height = target.height;
            return;
        }

        try {
            var iris = (IrisRenderingPipeline) (Object) this;
            vulkanite$pipeline.render(target.width, target.height, camera, iris.getStorageBuffers());
        } catch (RuntimeException | AssertionError failure) {
            LOGGER.error("Vulkanite stopped ray tracing after a render failure", failure);
            vulkanite$close();
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void vulkanite$destroy(CallbackInfo ci) {
        vulkanite$close();
    }

    @Unique
    private void vulkanite$close() {
        if (vulkanite$pipeline == null) return;
        RaytracingPackState.setActive(false);
        EntityGeometryCollector.INSTANCE.setRayTracingActive(false);
        vulkanite$pipeline.close();
        vulkanite$pipeline = null;
        Vulkanite.INSTANCE.clearWorld();
    }
}
