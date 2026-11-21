package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.entity.EntityTextureRegistry;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;

public class VulkanPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/RTPipeline");

    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final VCommandPool singleUsePool;

    private VRaytracePipeline[] raytracePipelines;
    private RaytracingShaderSet[] rtPasses;
    private VDescriptorSetLayout commonLayout;
    private VDescriptorSetLayout customtexLayout;
    private VDescriptorSetLayout storageBufferLayout;

    private VDescriptorPool commonDescriptorPool;
    private VDescriptorPool customtexDescriptorPool;
    private VDescriptorPool storageBufferDescriptorPool;

    private final VSampler sampler;
    private final CustomTextureBinding[] customTextures;

    private final SharedImageViewTracker[] customTextureViews;
    private final SharedImageViewTracker[] entityTextureViews;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;

    private final VImage fallbackImage;
    private final VImageView fallbackImageView;
    private final VBuffer fallbackMotionBuffer;
    private final FrameUniforms uniforms;

    private int fidx;
    private int frameId;
    private int frameCounter;
    private boolean loggedSsboBindingOnce = false;
    private boolean loggedEntityTextures;
    private VSemaphore previousSemaphore;
    private boolean destroyed;
    private final java.util.Set<VSemaphore> ownedSemaphores = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<me.cortex.vulkanite.lib.other.sync.VFence> ownedFences = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final me.cortex.vulkanite.lib.other.sync.VFence[] frameFences = new me.cortex.vulkanite.lib.other.sync.VFence[10];

    public static final Identifier LOCATION_BLOCKS = TextureAtlas.LOCATION_BLOCKS;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
            int[] ssboIds, CustomTextureBinding[] customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.customTextures = customTextures;
        try {
        this.singleUsePool = ctx.cmd.createSingleUsePool();
        this.uniforms = new FrameUniforms(ctx);

        this.customTextureViews = new SharedImageViewTracker[customTextures.length];
        for (int i = 0; i < customTextures.length; i++) {
            this.customTextureViews[i] = customTextures[i].view;
        }

        this.fallbackImage = ctx.memory.createImage2D(4, 4, 1, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        this.fallbackImageView = new VImageView(ctx, fallbackImage);
        this.fallbackMotionBuffer = ctx.memory.createBuffer(4L * Short.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        long fallbackMotionPtr = fallbackMotionBuffer.map();
        MemoryUtil.memSet(fallbackMotionPtr, 0, 4L * Short.BYTES);
        fallbackMotionBuffer.unmap();
        fallbackMotionBuffer.flush();

        this.entityTextureViews = new SharedImageViewTracker[EntityTextureRegistry.CAPACITY];
        for (int i = 0; i < entityTextureViews.length; i++) {
            int index = i;
            entityTextureViews[i] = new SharedImageViewTracker(ctx, () -> getEntityTexture(index));
        }

        this.blockAtlasView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            if (atlas != null) ((IVGImage) atlas).ensureShared();
            return atlas != null ? ((IVGImage) atlas).getVGImage().map(v -> (VImage) v).orElse((VImage) fallbackImage)
                    : (VImage) fallbackImage;
        });

        this.blockAtlasNormalView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            return atlas != null ? getPbrAtlas(atlas, h -> ((IVGImage) h.normalTexture()).getVGImage())
                    : (VImage) fallbackImage;
        });

        this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            return atlas != null ? getPbrAtlas(atlas, h -> ((IVGImage) h.specularTexture()).getVGImage())
                    : (VImage) fallbackImage;
        });

        transitionFallbackImageLayout();

        this.sampler = createSampler(VK_FILTER_NEAREST, 1.0f);

        buildLayoutsAndPipelines(ssboIds, passes);
        } catch (RuntimeException | AssertionError e) {
            destory();
            throw e;
        }
    }

    public void renderPostShadows(int width, int height, Camera camera, ShaderStorageBuffer[] ssbos) {
        if (ctx.sync.isDeviceLost())
            return;
        if (frameFences[fidx] != null) {
            ctx.sync.waitForFence(frameFences[fidx]);
            ctx.sync.checkFences();
        }
        this.singleUsePool.doReleases();
        for (var texture : customTextures) texture.refresh();

        final int[] signalBuffers = collectSharedSsbos(ssbos);
        final SharedTextures sharedTextures = collectSharedTextures();
        final int[] signalTextures = sharedTextures.ids();
        final int[] signalTexLayouts = sharedTextures.layouts();

        if (++frameCounter % 60 == 0) {
            LOGGER.debug("renderPostShadows frame={} fidx={} {}x{} ssboGlIds=[{}]",
                    frameCounter, fidx, width, height,
                    java.util.Arrays.toString(signalBuffers));
        }

        var in = own(ctx.sync.createSharedBinarySemaphore());
        in.glSignal(signalBuffers, signalTextures, signalTexLayouts);
        glFlush();

        int glErr = glGetError();
        if (glErr != GL_NO_ERROR && frameCounter % 60 == 0) {
            LOGGER.warn("GL error {} after glSignal at frame={}", glErr, frameCounter);
        }

        var tlasLink = own(ctx.sync.createBinarySemaphore());
        var tlas = accelerationManager.buildTLAS(in, tlasLink);
        if (tlas == null) {
            glFinish();
            release(tlasLink);
            release(in);
            return;
        }

        VBuffer uboBuffer = uniforms.update(fidx, camera, frameId++);

        long commonSet = commonDescriptorPool.get(fidx);
        long ctexSet = customtexDescriptorPool.get(fidx);
        long ssboSet = storageBufferDescriptorPool.get(fidx);
        updateDescriptorSets(commonSet, ctexSet, ssboSet, uboBuffer, tlas, ssbos);

        var cmd = singleUsePool.createCommandBuffer();
        cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        recordExternalOwnership(cmd, ssbos, true);
        recordTraceRays(cmd, commonSet, ctexSet, ssboSet, width, height);
        recordExternalOwnership(cmd, ssbos, false);
        cmd.end();

        var out = own(ctx.sync.createSharedBinarySemaphore());
        var fence = ctx.sync.createFence();
        ownedFences.add(fence);
        ctx.cmd.submit(0, new VCmdBuff[] { cmd }, new VSemaphore[] { tlasLink },
                new int[] { VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR }, new VSemaphore[] { out }, fence);

        int frameSlot = fidx;
        frameFences[frameSlot] = fence;
        var semCapture = previousSemaphore;
        previousSemaphore = out;
        ctx.sync.addCallback(fence, () -> {
            release(tlasLink);
            release(in);
            cmd.enqueueFree();
            fence.free();
            ownedFences.remove(fence);
            frameFences[frameSlot] = null;
            if (semCapture != null)
                release(semCapture);
        });

        out.glWait(signalBuffers, signalTextures, signalTexLayouts);
        glFlush();

        fidx = (fidx + 1) % 10;
    }

    public void destory() {
        if (destroyed) return;
        destroyed = true;
        ctx.cmd.waitIdle();
        glFinish();
        ctx.sync.drainCallbacks();
        if (raytracePipelines != null) for (var pass : raytracePipelines) if (pass != null) pass.free();
        if (commonLayout != null) commonLayout.free();
        if (customtexLayout != null) customtexLayout.free();
        if (storageBufferLayout != null) storageBufferLayout.free();
        if (commonDescriptorPool != null) commonDescriptorPool.free();
        if (customtexDescriptorPool != null) customtexDescriptorPool.free();
        if (storageBufferDescriptorPool != null) storageBufferDescriptorPool.free();
        ctx.sync.checkFences();
        if (singleUsePool != null) {
            singleUsePool.doReleases();
            singleUsePool.free();
        }
        // Includes allocations from a frame that failed before registering its callback.
        ownedSemaphores.forEach(VSemaphore::free);
        ownedSemaphores.clear();
        ownedFences.forEach(me.cortex.vulkanite.lib.other.sync.VFence::free);
        ownedFences.clear();
        for (CustomTextureBinding texture : customTextures) texture.free();
        if (entityTextureViews != null) for (SharedImageViewTracker view : entityTextureViews) if (view != null) view.free();
        if (blockAtlasView != null) blockAtlasView.free();
        if (blockAtlasNormalView != null) blockAtlasNormalView.free();
        if (blockAtlasSpecularView != null) blockAtlasSpecularView.free();
        if (fallbackImageView != null) fallbackImageView.free();
        if (fallbackImage != null) fallbackImage.free();
        if (fallbackMotionBuffer != null) fallbackMotionBuffer.free();
        if (sampler != null) sampler.free();
        if (uniforms != null) uniforms.free();
    }

    private <T extends VSemaphore> T own(T semaphore) {
        ownedSemaphores.add(semaphore);
        return semaphore;
    }

    private void release(VSemaphore semaphore) {
        if (ownedSemaphores.remove(semaphore)) semaphore.free();
    }

    private static void applyImageBarrier(VkImageMemoryBarrier barrier, VImage image, int targetLayout,
            int targetAccess) {
        barrier.sType$Default()
                .sType$Default()
                .image(image.image())
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(targetLayout)
                .srcAccessMask(0)
                .dstAccessMask(targetAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .subresourceRange(e -> e.levelCount(image.mipLayers).layerCount(1)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT));
    }

    private VImage getPbrAtlas(AbstractTexture atlas,
            java.util.function.Function<PBRTextureHolder, Optional<VGImage>> mapper) {
        int atlasGlId = atlas.getTexture().iris$getGlId();
        var holder = PBRTextureManager.INSTANCE.getOrLoadHolder(atlasGlId);
        if (holder != null) {
            ((IVGImage) holder.normalTexture()).ensureShared();
            ((IVGImage) holder.specularTexture()).ensureShared();
        }
        return holder != null ? mapper.apply(holder).map(v -> (VImage) v).orElse(fallbackImage) : fallbackImage;
    }

    private VImage getEntityTexture(int index) {
        Identifier location = EntityTextureRegistry.INSTANCE.get(index);
        if (location == null) {
            return null;
        }
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (texture != null) ((IVGImage) texture).ensureShared();
        return texture != null ? ((IVGImage) texture).getVGImage().map(image -> (VImage) image).orElse(null) : null;
    }

    private void transitionFallbackImageLayout() {
        try (var stack = stackPush()) {
            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            var barriers = VkImageMemoryBarrier.calloc(1, stack);
            applyImageBarrier(barriers.get(0), fallbackImage, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, barriers);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            vkCmdClearColorImage(cmd.buffer, fallbackImage.image(), VK_IMAGE_LAYOUT_GENERAL,
                    VkClearColorValue.calloc(stack).float32(stack.floats(1, 1, 1, 1)), range);
            cmd.encodeBufferBarrier(fallbackMotionBuffer, 0, VK_WHOLE_SIZE,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0, VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT), null, null);
            cmd.end();
            ctx.cmd.submitOnceAndWait(0, cmd);
        }
    }

    private VSampler createSampler(int filter, float anisotropy) {
        return new VSampler(ctx, a -> a.magFilter(filter)
                .minFilter(filter)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .minLod(0.0f)
                .maxLod(VK_LOD_CLAMP_NONE)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(anisotropy));
    }

    private void buildLayoutsAndPipelines(int[] ssboIds, RaytracingShaderSet[] passes) {
        commonLayout = new DescriptorSetLayoutBuilder()
                .binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_ALL)
                .binding(1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_SHADER_STAGE_ALL)
                .binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_ALL)
                .binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, EntityTextureRegistry.CAPACITY,
                        VK_SHADER_STAGE_ALL)
                .build(ctx);

        DescriptorSetLayoutBuilder ctexLayoutBuilder = new DescriptorSetLayoutBuilder();
        for (int i = 0; i < customTextureViews.length; i++) {
            ctexLayoutBuilder.binding(i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL);
        }
        customtexLayout = ctexLayoutBuilder.build(ctx);

        DescriptorSetLayoutBuilder ssboLayoutBuilder = new DescriptorSetLayoutBuilder();
        for (int id : ssboIds) {
            ssboLayoutBuilder.binding(id, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_ALL);
        }
        storageBufferLayout = ssboLayoutBuilder.build(ctx);

        commonDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                EntityTextureRegistry.CAPACITY, commonLayout.types);
        commonDescriptorPool.allocateSets(commonLayout);
        customtexDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                customtexLayout.types);
        customtexDescriptorPool.allocateSets(customtexLayout);
        storageBufferDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                storageBufferLayout.types);
        storageBufferDescriptorPool.allocateSets(storageBufferLayout);

        this.rtPasses = passes;
        raytracePipelines = new VRaytracePipeline[passes.length];
        for (int i = 0; i < passes.length; i++) {
            var builder = new RaytracePipelineBuilder()
                    .addLayout(commonLayout)
                    .addLayout(accelerationManager.getGeometryLayout())
                    .addLayout(customtexLayout)
                    .addLayout(storageBufferLayout);
            passes[i].apply(builder);
            raytracePipelines[i] = builder.build(ctx, 1);
        }
    }

    private int[] collectSharedSsbos(ShaderStorageBuffer[] ssbos) {
        final int[] sharedSsboIds = new int[ssbos.length];
        final int[] count = { 0 };
        for (ShaderStorageBuffer ssbo : ssbos) {
            if (ssbo == null) continue;
            ((IVGBuffer) ssbo).getBuffer().ifPresent(buf -> sharedSsboIds[count[0]++] = buf.glId);
        }
        return Arrays.copyOf(sharedSsboIds, count[0]);
    }

    private SharedTextures collectSharedTextures() {
        ArrayList<Integer> texIds = new ArrayList<>();
        for (SharedImageViewTracker view : new SharedImageViewTracker[] { blockAtlasView, blockAtlasNormalView,
                blockAtlasSpecularView }) {
            VImage img = view.getImage();
            if (img instanceof VGImage vg && !texIds.contains(vg.glId)) {
                texIds.add(vg.glId);
            }
        }
        for (SharedImageViewTracker view : customTextureViews) {
            VImage image = view.getImage();
            if (image instanceof VGImage vg && !texIds.contains(vg.glId)) {
                texIds.add(vg.glId);
            }
        }
        int entityTextureCount = EntityTextureRegistry.INSTANCE.size();
        int sharedEntityTextureCount = 0;
        for (int i = 0; i < entityTextureCount; i++) {
            VImage image = entityTextureViews[i].getImage();
            if (image instanceof VGImage vg && !texIds.contains(vg.glId)) {
                texIds.add(vg.glId);
                sharedEntityTextureCount++;
            }
        }
        if (!loggedEntityTextures && entityTextureCount > 0) {
            LOGGER.info("Entity RT textures: {} registered, {} Vulkan-shared",
                    entityTextureCount, sharedEntityTextureCount);
            for (int i = 0; i < Math.min(entityTextureCount, 8); i++) {
                LOGGER.info("  entity texture [{}] {} shared={}", i, EntityTextureRegistry.INSTANCE.get(i),
                        entityTextureViews[i].getImage() instanceof VGImage);
            }
            loggedEntityTextures = true;
        }
        int[] ids = texIds.stream().mapToInt(x -> x).toArray();
        int[] layouts = new int[ids.length];
        Arrays.fill(layouts, GL_LAYOUT_GENERAL_EXT);
        return new SharedTextures(ids, layouts);
    }

    private void updateDescriptorSets(long commonSet, long ctexSet, long ssboSet, VBuffer uboBuffer,
            VAccelerationStructure tlas, ShaderStorageBuffer[] ssbos) {
        VImageView[] entityViews = new VImageView[entityTextureViews.length];
        for (int i = 0; i < entityViews.length; i++) {
            entityViews[i] = entityTextureViews[i].getView();
        }

        VBuffer entityMotionBuffer = accelerationManager.getEntityMotionBuffer();
        new DescriptorUpdateBuilder(ctx, 7, fallbackImageView)
                .set(commonSet)
                .buffer(2, entityMotionBuffer != null ? entityMotionBuffer : fallbackMotionBuffer)
                .uniform(0, uboBuffer)
                .acceleration(1, tlas) // 此时方法重载完美适配！
                .imageSampler(3, blockAtlasView.getView(), sampler)
                .imageSampler(4, blockAtlasNormalView.getView(), sampler)
                .imageSampler(5, blockAtlasSpecularView.getView(), sampler)
                .imageSamplerArray(6, VK_IMAGE_LAYOUT_GENERAL, entityViews, sampler)
                .apply();

        final var ctexUpdater = new DescriptorUpdateBuilder(ctx, customTextureViews.length, fallbackImageView)
                .set(ctexSet);
        for (int i = 0; i < customTextureViews.length; i++) {
            ctexUpdater.imageSampler(i, customTextureViews[i].getView(), customTextures[i].sampler);
        }
        ctexUpdater.apply();

        final var ssboUpdater = new DescriptorUpdateBuilder(ctx, ssbos.length, fallbackImageView).set(ssboSet);
        final int[] boundCount = { 0 };
        // Track VkBuffer handles to diagnose whether descriptor updates
        // actually switch to new buffers after resize
        StringBuilder ssboVkHandles = (frameCounter % 60 == 0) ? new StringBuilder() : null;
        for (ShaderStorageBuffer ssbo : ssbos) {
            if (ssbo == null) continue;
            ((IVGBuffer) ssbo).getBuffer().ifPresentOrElse(buf -> {
                ssboUpdater.buffer(ssbo.getIndex(), buf);
                boundCount[0]++;
                if (ssboVkHandles != null) {
                    ssboVkHandles.append(ssbo.getIndex()).append("=0x")
                            .append(Long.toHexString(buf.buffer())).append(" ");
                }
            }, () -> {
                if (!loggedSsboBindingOnce) {
                    LOGGER.warn("SSBO index={} has no Vulkan-shared buffer, RT writes to this binding are lost",
                            ssbo.getIndex());
                }
            });
        }
        if (!loggedSsboBindingOnce) {
            LOGGER.debug("renderPostShadows SSBO binding: {}/{} buffers bound to Vulkan descriptor set", boundCount[0],
                    ssbos.length);
            for (ShaderStorageBuffer ssbo : ssbos) {
                if (ssbo == null) continue;
                LOGGER.info("  ssbo index={} glId={} vkShared={}", ssbo.getIndex(), ssbo.getId(),
                        ((IVGBuffer) ssbo).getBuffer() != null);
            }
            loggedSsboBindingOnce = true;
        }
        if (ssboVkHandles != null) {
            LOGGER.debug("renderPostShadows frame={} ssbo VkBuffer handles: {}", frameCounter,
                    ssboVkHandles.toString());
        }
        ssboUpdater.apply();
    }

    private void recordExternalOwnership(VCmdBuff cmd, ShaderStorageBuffer[] ssbos, boolean acquire) {
        try (var stack = stackPush()) {
            int entityTextureCount = EntityTextureRegistry.INSTANCE.size();
            var barriers = VkImageMemoryBarrier.calloc(3 + customTextureViews.length + entityTextureCount, stack);
            var images = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VImage, Boolean>());
            images.add(blockAtlasView.getImage());
            var image = blockAtlasNormalView.getImage();
            images.add(image);
            image = blockAtlasSpecularView.getImage();
            images.add(image);
            for (SharedImageViewTracker customtexView : customTextureViews) {
                images.add(customtexView.getImage());
            }
            for (int i = 0; i < entityTextureCount; i++) {
                images.add(entityTextureViews[i].getImage());
            }
            for (var shared : images) {
                if (!(shared instanceof VGImage)) continue;
                barriers.get().sType$Default().image(shared.image())
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(acquire ? VK_QUEUE_FAMILY_EXTERNAL : ctx.queueFamily)
                        .dstQueueFamilyIndex(acquire ? ctx.queueFamily : VK_QUEUE_FAMILY_EXTERNAL)
                        .srcAccessMask(acquire ? 0 : VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(acquire ? VK_ACCESS_SHADER_READ_BIT : 0)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .levelCount(shared.mipLayers).layerCount(1));
            }
            barriers.limit(barriers.position());
            barriers.rewind();
            var buffers = VkBufferMemoryBarrier.calloc(ssbos.length, stack);
            for (var ssbo : ssbos) {
                if (ssbo == null) continue;
                ((IVGBuffer) ssbo).getBuffer().ifPresent(shared -> buffers.get().sType$Default()
                        .buffer(shared.buffer()).offset(0).size(VK_WHOLE_SIZE)
                        .srcQueueFamilyIndex(acquire ? VK_QUEUE_FAMILY_EXTERNAL : ctx.queueFamily)
                        .dstQueueFamilyIndex(acquire ? ctx.queueFamily : VK_QUEUE_FAMILY_EXTERNAL)
                        .srcAccessMask(acquire ? 0 : VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(acquire ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0));
            }
            buffers.flip();
            vkCmdPipelineBarrier(cmd.buffer,
                    acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    acquire ? VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, null, buffers, barriers);
        }
    }

    private record SharedTextures(int[] ids, int[] layouts) {
    }

    private void recordTraceRays(VCmdBuff cmd, long commonSet, long ctexSet, long ssboSet, int width, int height) {
        int prevGroup = Integer.MIN_VALUE;
        try (var stack = stackPush()) {
            var memBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            for (int i = 0; i < raytracePipelines.length; i++) {
                var pass = rtPasses[i];
                if (i > 0 && pass.group != prevGroup) {
                    vkCmdPipelineBarrier(cmd.buffer,
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                            0, memBarrier, null, null);
                }
                prevGroup = pass.group;

                int dw = dispatchDim(pass.dispatchW, width);
                int dh = dispatchDim(pass.dispatchH, height);
                int dd = Math.max(1, (int) pass.dispatchD);
                raytracePipelines[i].bind(cmd);
                raytracePipelines[i].bindDSet(cmd, commonSet, accelerationManager.getGeometrySet(), ctexSet, ssboSet);
                raytracePipelines[i].trace(cmd, dw, dh, dd);
            }
        }
    }

    private static int dispatchDim(float val, int screenSize) {
        return val > 1.0f ? (int) val : Math.max(1, (int) (screenSize * val));
    }

    private static class FrameUniforms {
        // corners[4](4×16B) + viewInverse(64B) + frameId(4B) + flags(4B) +
        // world_type(4B)
        private static final int UBO_SIZE = 256;
        private final VBuffer[] buffers = new VBuffer[10];

        public FrameUniforms(VContext ctx) {
            try {
            for (int i = 0; i < 10; i++) {
                buffers[i] = ctx.memory.createBuffer(UBO_SIZE,
                        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                        0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            }
            } catch (RuntimeException | AssertionError e) {
                free();
                throw e;
            }
        }

        public VBuffer update(int fidx, Camera camera, int frameId) {
            VBuffer buf = buffers[fidx];
            long ptr = buf.map();
            MemoryUtil.memSet(ptr, 0, UBO_SIZE);

            ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, UBO_SIZE);
            Vector3f tmpv3 = new Vector3f();
            Matrix4f invProjMatrix = new Matrix4f();
            Matrix4f invViewMatrix = new Matrix4f();

            CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);
            new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())
                    .translate(camera.position().toVector3f().negate()).invert(invViewMatrix);

            invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
            invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4 * Float.BYTES, bb);
            invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8 * Float.BYTES, bb);
            invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12 * Float.BYTES, bb);
            invViewMatrix.get(Float.BYTES * 16, bb);

            // Sun/moon position removed — obtained from Iris FrameData SSBO
            // (lightDir_global)
            bb.putInt(Float.BYTES * 32, frameId);

            int flags = Uniforms.isEyeInWater() & 3;
            flags += Uniforms.getWorld().dimensionType().hasSkyLight() ? 4 : 0;

            bb.putInt(Float.BYTES * 33, flags);
            bb.putInt(Float.BYTES * 34, switch (net.irisshaders.iris.Iris.getCurrentDimension().getName()) {
                case "the_end" -> 2;
                case "the_nether" -> 1;
                default -> 0; // overworld (and custom dims default to overworld)
            });

            buf.unmap();
            buf.flush();
            return buf;
        }

        public void free() {
            for (VBuffer buf : buffers) {
                if (buf != null)
                    buf.free();
            }
        }
    }
}
