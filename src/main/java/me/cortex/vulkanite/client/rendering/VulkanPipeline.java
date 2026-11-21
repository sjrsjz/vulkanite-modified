package me.cortex.vulkanite.client.rendering;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.rendering.entity.EntityTextureRegistry;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vulkan.VulkanRenderBackend;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK10.*;

/** Records ray tracing in Iris's Vulkan frame using RenderPearl-owned pack resources. */
public final class VulkanPipeline implements AutoCloseable {
    private static final int RT_STAGES = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR
            | VK_SHADER_STAGE_INTERSECTION_BIT_KHR | VK_SHADER_STAGE_CALLABLE_BIT_KHR;
    private static final int UNIFORM_BYTES = 256;

    private final VContext context;
    private final AccelerationManager scene;
    private final RaytracingShaderSet[] passes;
    private final int[] bufferBindings;
    private final List<CustomTexture> customTextures = new ArrayList<>();
    private final List<FrameBindings> frameBindings = new ArrayList<>();
    private final ArrayDeque<FrameBindings> availableFrames = new ArrayDeque<>();
    private final VRaytracePipeline[] pipelines;
    private VDescriptorSetLayout commonLayout;
    private VDescriptorSetLayout textureLayout;
    private VDescriptorSetLayout storageLayout;
    private VSampler atlasSampler;
    private VImage fallback;
    private VImageView fallbackView;
    private VBuffer fallbackMotion;
    private boolean closed;
    private int frameId;

    public VulkanPipeline(VContext context, AccelerationManager scene, RaytracingShaderSet[] passes,
            int[] bufferBindings, List<TextureAccess> customTextures) {
        this.context = context;
        this.scene = scene;
        this.passes = passes;
        this.bufferBindings = bufferBindings;
        this.pipelines = new VRaytracePipeline[passes.length];
        try {
            for (var texture : customTextures) this.customTextures.add(new CustomTexture(texture));
            createFallbacks();
            createLayouts();
            for (int i = 0; i < passes.length; i++) {
                var builder = new RaytracePipelineBuilder().addLayout(commonLayout).addLayout(scene.getGeometryLayout())
                        .addLayout(textureLayout).addLayout(storageLayout);
                passes[i].apply(builder);
                pipelines[i] = builder.build(context, passes[i].maxDepth);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public void render(int width, int height, Camera camera, ShaderStorageBufferHolder buffers) {
        if (closed) return;
        var backend = (VulkanRenderBackend) Iris.getRenderBackend();
        backend.textures.flush();
        backend.sync(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
        var command = context.commands();
        var tlas = scene.recordScene(command);
        var bindings = acquireBindings();
        updateUniforms(bindings.uniforms, camera);
        updateDescriptors(bindings, tlas, buffers);

        for (int i = 0; i < pipelines.length; i++) {
            if (i > 0 && passes[i].group != passes[i - 1].group) {
                command.barrier(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
            }
            var pass = passes[i];
            pipelines[i].bind(command);
            pipelines[i].bindDSet(command, bindings.common.get(0), scene.getGeometrySet(),
                    bindings.textures.get(0), bindings.storage.get(0));
            pipelines[i].trace(command, dispatch(pass.dispatchW, width), dispatch(pass.dispatchH, height),
                    Math.max(1, (int) pass.dispatchD));
        }
    }

    private void createLayouts() {
        commonLayout = new DescriptorSetLayoutBuilder()
                .binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, RT_STAGES)
                .binding(1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, RT_STAGES)
                .binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, RT_STAGES)
                .binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, RT_STAGES)
                .binding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, RT_STAGES)
                .binding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, RT_STAGES)
                .binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, EntityTextureRegistry.CAPACITY, RT_STAGES)
                .build(context);
        var textures = new DescriptorSetLayoutBuilder();
        for (int i = 0; i < customTextures.size(); i++) {
            textures.binding(i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, RT_STAGES);
        }
        textureLayout = textures.build(context);
        var storage = new DescriptorSetLayoutBuilder();
        for (int binding : bufferBindings) storage.binding(binding, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, RT_STAGES);
        storageLayout = storage.build(context);
    }

    private void createFallbacks() {
        fallback = context.memory.createImage2D(1, 1, 1, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        fallbackView = new VImageView(context, fallback);
        fallbackMotion = hostBuffer(4L * Short.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        long mapped = fallbackMotion.map();
        try {
            MemoryUtil.memSet(mapped, 0, fallbackMotion.size());
            fallbackMotion.flush();
        } finally {
            fallbackMotion.unmap();
        }
        atlasSampler = new VSampler(context, info -> info.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(VK_LOD_CLAMP_NONE)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE));

        var command = context.commands();
        try (var stack = stackPush()) {
            var barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(fallback.image())
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1));
            vkCmdPipelineBarrier(command.buffer(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, barrier);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(1).levelCount(1);
            vkCmdClearColorImage(command.buffer(), fallback.image(), VK_IMAGE_LAYOUT_GENERAL,
                    VkClearColorValue.calloc(stack).float32(stack.floats(1, 1, 1, 1)), range);
        }
        command.barrier(VK_PIPELINE_STAGE_2_CLEAR_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT_KHR);
    }

    private VBuffer hostBuffer(long size, int usage) {
        return context.memory.createBuffer(size, usage, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
    }

    private FrameBindings acquireBindings() {
        var reused = availableFrames.pollFirst();
        if (reused == null) {
            reused = new FrameBindings();
            frameBindings.add(reused);
        }
        var frame = reused;
        context.retire(() -> {
            if (!closed) availableFrames.addLast(frame);
        });
        return frame;
    }

    private final class FrameBindings {
        VDescriptorPool common;
        VDescriptorPool textures;
        VDescriptorPool storage;
        VBuffer uniforms;

        FrameBindings() {
            try {
                common = new VDescriptorPool(context, 0, 1, EntityTextureRegistry.CAPACITY, commonLayout.types);
                common.allocateSets(commonLayout);
                textures = new VDescriptorPool(context, 0, 1, textureLayout.types);
                textures.allocateSets(textureLayout);
                storage = new VDescriptorPool(context, 0, 1, storageLayout.types);
                storage.allocateSets(storageLayout);
                uniforms = hostBuffer(UNIFORM_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            } catch (RuntimeException | Error failure) {
                free();
                throw failure;
            }
        }

        void free() {
            if (common != null) common.free();
            if (textures != null) textures.free();
            if (storage != null) storage.free();
            if (uniforms != null) uniforms.free();
        }
    }

    private void updateDescriptors(FrameBindings frame, VAccelerationStructure tlas, ShaderStorageBufferHolder buffers) {
        var textureManager = Minecraft.getInstance().getTextureManager();
        var atlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        var pbr = PBRTextureManager.INSTANCE.getOrLoadHolder(atlas.getTexture());
        long atlasView = textureView(atlas.getTextureView());
        long normalView = textureView(pbr.normalTexture().getTextureView());
        long specularView = textureView(pbr.specularTexture().getTextureView());

        long[] entities = new long[EntityTextureRegistry.CAPACITY];
        Arrays.fill(entities, fallbackView.view);
        var registry = EntityTextureRegistry.INSTANCE;
        for (int i = 0; i < registry.size(); i++) {
            var texture = textureManager.getTexture(registry.get(i));
            entities[i] = textureView(texture.getTextureView());
        }

        var motion = scene.getEntityMotionBuffer();
        try (var descriptors = new DescriptorUpdateBuilder(context, 7)) {
            descriptors.set(frame.common.get(0)).uniform(0, frame.uniforms).acceleration(1, tlas)
                    .buffer(2, motion == null ? fallbackMotion : motion)
                    .sampledImage(3, atlasView, atlasSampler.sampler)
                    .sampledImage(4, normalView, atlasSampler.sampler)
                    .sampledImage(5, specularView, atlasSampler.sampler)
                    .sampledImages(6, entities, atlasSampler.sampler)
                    .apply();
        }

        try (var descriptors = new DescriptorUpdateBuilder(context, customTextures.size())) {
            descriptors.set(frame.textures.get(0));
            for (int i = 0; i < customTextures.size(); i++) {
                var texture = customTextures.get(i);
                descriptors.sampledImage(i, texture.view(), texture.sampler());
            }
            descriptors.apply();
        }

        try (var descriptors = new DescriptorUpdateBuilder(context, bufferBindings.length)) {
            descriptors.set(frame.storage.get(0));
            for (int binding : bufferBindings) {
                var buffer = (VulkanGpuBuffer) buffers.getBuffer(binding).buffer();
                descriptors.buffer(binding, buffer.vkBuffer(), 0, buffer.size());
            }
            descriptors.apply();
        }
    }

    private static long textureView(GpuTextureView view) {
        return ((VulkanGpuTextureView) view).vkImageView();
    }

    private final class CustomTexture {
        private final SamplerHolder.TextureBinding binding;
        private final VSampler sampler;

        CustomTexture(TextureAccess texture) {
            binding = texture.samplerBinding();
            if (binding.nativeSampler() != null) {
                sampler = null;
                return;
            }
            var description = binding.sampler().get().description();
            int filter = description.linear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            boolean rectangle = texture.getType() == TextureType.TEXTURE_RECTANGLE;
            int address = rectangle || description.clamp() ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
            sampler = new VSampler(context, info -> info.magFilter(filter).minFilter(filter)
                    .mipmapMode(rectangle || !description.linearMips() ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(address).addressModeV(address).addressModeW(address)
                    .maxLod(!rectangle && description.mipmapped() ? VK_LOD_CLAMP_NONE : 0)
                    .unnormalizedCoordinates(rectangle));
        }

        long view() {
            if (binding.image() != null) return binding.image().get().getView();
            return textureView(binding.texture().get());
        }

        long sampler() {
            return sampler == null ? ((VulkanGpuSampler) binding.nativeSampler().get()).vkSampler() : sampler.sampler;
        }

        void free() {
            if (sampler != null) sampler.free();
        }
    }

    private void updateUniforms(VBuffer buffer, Camera camera) {
        long ptr = buffer.map();
        try {
            MemoryUtil.memSet(ptr, 0, UNIFORM_BYTES);
            var bytes = MemoryUtil.memByteBuffer(ptr, UNIFORM_BYTES);
            var projection = CapturedRenderingState.INSTANCE.getGbufferProjection().invert(new Matrix4f());
            var view = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())
                    .translate(camera.position().toVector3f().negate()).invert();
            var corner = new Vector3f();
            projection.transformProject(-1, -1, 0, 1, corner).get(0, bytes);
            projection.transformProject(1, -1, 0, 1, corner).get(16, bytes);
            projection.transformProject(-1, 1, 0, 1, corner).get(32, bytes);
            projection.transformProject(1, 1, 0, 1, corner).get(48, bytes);
            view.get(64, bytes);
            bytes.putInt(128, frameId++);
            int flags = switch (camera.getFluidInCamera()) {
                case WATER -> 1;
                case LAVA -> 2;
                case POWDER_SNOW -> 3;
                default -> 0;
            };
            if (Minecraft.getInstance().level.dimensionType().hasSkyLight()) flags |= 4;
            bytes.putInt(132, flags);
            bytes.putInt(136, switch (Iris.getCurrentDimension().getName()) {
                case "the_nether" -> 1;
                case "the_end" -> 2;
                default -> 0;
            });
            buffer.flush();
        } finally {
            buffer.unmap();
        }
    }

    private static int dispatch(float value, int extent) {
        return Math.max(1, (int) (value > 1.0f ? value : value * extent));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        context.retire(() -> {
            for (var pipeline : pipelines) if (pipeline != null) pipeline.free();
            frameBindings.forEach(FrameBindings::free);
            customTextures.forEach(CustomTexture::free);
            if (commonLayout != null) commonLayout.free();
            if (textureLayout != null) textureLayout.free();
            if (storageLayout != null) storageLayout.free();
            if (fallbackView != null) fallbackView.free();
            if (fallback != null) fallback.free();
            if (fallbackMotion != null) fallbackMotion.free();
            if (atlasSampler != null) atlasSampler.free();
        });
    }
}
