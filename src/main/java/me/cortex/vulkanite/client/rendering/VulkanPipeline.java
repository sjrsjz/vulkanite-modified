package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
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
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import net.coderbot.iris.gl.buffer.ShaderStorageBuffer;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.gl.buffer.ShaderStorageInfo;
import net.coderbot.iris.texture.pbr.PBRTextureHolder;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.CelestialUniforms;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.dimension.DimensionType;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;

import static net.coderbot.iris.uniforms.CelestialUniforms.getSunAngle;
import static net.coderbot.iris.uniforms.CelestialUniforms.isDay;
import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipeline {
    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final VCommandPool singleUsePool;

    private VRaytracePipeline[] raytracePipelines;
    private VDescriptorSetLayout commonLayout;
    private VDescriptorSetLayout customtexLayout;
    private VDescriptorSetLayout storageBufferLayout;

    private VDescriptorPool commonDescriptorPool;
    private VDescriptorPool customtexDescriptorPool;
    private VDescriptorPool storageBufferDescriptorPool;

    private final VSampler sampler;
    private final VSampler ctexSampler;

    private final SharedImageViewTracker composite0mainView;
    private final SharedImageViewTracker[] customTextureViews;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;

    private final VImage placeholderImage;
    private final VImageView placeholderImageView;

    private int fidx;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes, int[] ssboIds, VGImage[] customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.singleUsePool = ctx.cmd.createSingleUsePool();

        {
            this.customTextureViews = new SharedImageViewTracker[customTextures.length];
            for(int i = 0; i < customTextures.length; i++) {
                int index = i;
                this.customTextureViews[i] = new SharedImageViewTracker(ctx, ()->customTextures[index]);
            }

            this.composite0mainView = new SharedImageViewTracker(ctx, null);
            this.blockAtlasView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager().getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
                return ((IVGImage)blockAtlas).getVGImage();
            });
            this.blockAtlasNormalView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager().getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
                PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());//((TextureAtlasExtension)blockAtlas).getPBRHolder()
                return ((IVGImage)holder.getNormalTexture()).getVGImage();
            });
            this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = MinecraftClient.getInstance().getTextureManager().getTexture(new Identifier("minecraft", "textures/atlas/blocks.png"));
                PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlas.getGlId());//((TextureAtlasExtension)blockAtlas).getPBRHolder()
                return ((IVGImage)holder.getSpecularTexture()).getVGImage();
            });
            this.placeholderImage = ctx.memory.createImage2D(4, 4, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            this.placeholderImageView = new VImageView(ctx, placeholderImage);

            try (var stack = stackPush()) {
                var cmd = singleUsePool.createCommandBuffer();
                cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

                var barriers = VkImageMemoryBarrier.calloc(1, stack);
                applyImageBarrier(barriers.get(0), placeholderImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_MEMORY_READ_BIT);
                vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, null, null, barriers);
                cmd.end();

                ctx.cmd.submit(0, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)));

                Vulkanite.INSTANCE.addSyncedCallback(cmd::enqueueFree);
            }
        }

        this.sampler = new VSampler(ctx, a->a.magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        this.ctexSampler = new VSampler(ctx, a->a.magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        try {
            commonLayout = new DescriptorSetLayoutBuilder()
                    .binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_ALL)// camera data
                    .binding(1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_SHADER_STAGE_ALL)// funni acceleration buffer
                    .binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)//block texture
                    .binding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)//block texture normal
                    .binding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)//block texture specular
                    // Reordered these so output texture is last... this means you can dynamically add more output textures without messing other ids
                    .binding(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_ALL)// output texture
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

            //TODO: use frameahead count instead of just... 10
            commonDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10, commonLayout.types);
            commonDescriptorPool.allocateSets(commonLayout);

            customtexDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10, customtexLayout.types);
            customtexDescriptorPool.allocateSets(customtexLayout);

            storageBufferDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10, storageBufferLayout.types);
            storageBufferDescriptorPool.allocateSets(storageBufferLayout);

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

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void applyImageBarrier(VkImageMemoryBarrier barrier, VImage image, int targetLayout, int targetAccess) {
        barrier.sType$Default()
                .sType$Default()
                .image(image.image())
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(targetLayout)
                .srcAccessMask(0)
                .dstAccessMask(targetAccess)
                .subresourceRange(e->e.levelCount(1).layerCount(1).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT));
    }


    private VSemaphore previousSemaphore;

    private int frameId;

    private static RegistryKey<DimensionType> of(String id) {
        return RegistryKey.of(RegistryKeys.DIMENSION_TYPE, new Identifier(id));
    }


    public void renderPostShadows(VGImage outImg, Camera camera, ShaderStorageBuffer[] ssbos) {
        this.singleUsePool.doReleases();
        PBRTextureManager.notifyPBRTexturesChanged();

        var in = ctx.sync.createSharedBinarySemaphore();
        in.glSignal(new int[0], new int[]{outImg.glId}, new int[]{GL_LAYOUT_GENERAL_EXT});
        glFlush();

        var tlasLink = ctx.sync.createBinarySemaphore();

        var tlas = accelerationManager.buildTLAS(in, tlasLink);
        if (tlas == null) {
            glFinish();
            tlasLink.free();
            in.free();
            return;
        }

        var out = ctx.sync.createSharedBinarySemaphore();
        VBuffer uboBuffer;
        {
            uboBuffer = ctx.memory.createBuffer(1024,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            long ptr = uboBuffer.map();
            MemoryUtil.memSet(ptr, 0, 1024);
            {
                ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, 1024);

                Vector3f tmpv3 = new Vector3f();
                Matrix4f invProjMatrix = new Matrix4f();
                Matrix4f invViewMatrix = new Matrix4f();

                CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);
                new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).translate(camera.getPos().toVector3f().negate()).invert(invViewMatrix);

                invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
                invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4*Float.BYTES, bb);
                invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8*Float.BYTES, bb);
                invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12*Float.BYTES, bb);
                invViewMatrix.get(Float.BYTES * 16, bb);

                Uniforms.getSunPosition().get(Float.BYTES * 32, bb);
                Uniforms.getMoonPosition().get(Float.BYTES * 36, bb);

                bb.putInt(Float.BYTES * 40, frameId++);

                int flags = Uniforms.isEyeInWater()&3; 
                flags += Uniforms.getWorld().getDimension().hasSkyLight()?4:0;
                flags += Uniforms.getWorld().getDimensionEffects().shouldBrightenLighting() ? 8 : 0;
                
                enum World {
                    OVERWORLD,
                    THE_NETHER,
                    THE_END,
                    OVERWORLD_CAVES
                }
                World world_type;

                if (Uniforms.getWorld().getDimensionKey().equals(of("overworld"))) {
                    world_type = World.OVERWORLD;
                } else if (Uniforms.getWorld().getDimensionKey().equals(of("the_nether"))) {
                    world_type = World.THE_NETHER;
                } else if (Uniforms.getWorld().getDimensionKey().equals(of("the_end"))) {
                    world_type = World.THE_END;
                } else if (Uniforms.getWorld().getDimensionKey().equals(of("overworld_caves"))) {
                    world_type = World.OVERWORLD_CAVES;
                } else {
                    world_type = World.OVERWORLD;
                }
                

                bb.putInt(Float.BYTES * 41, flags);
                bb.putInt(Float.BYTES * 42, world_type.ordinal());
                bb.rewind();
            }
            uboBuffer.unmap();
            uboBuffer.flush();

            long commonSet = commonDescriptorPool.get(fidx);
            long ctexSet = customtexDescriptorPool.get(fidx);
            long ssboSet = storageBufferDescriptorPool.get(fidx);

            var updater = new DescriptorUpdateBuilder(ctx, 7, placeholderImageView)
                    .set(commonSet)
                    .uniform(0, uboBuffer)
                    .acceleration(1, tlas)
                    .imageSampler(3, blockAtlasView.getView(), sampler)
                    .imageSampler(4, blockAtlasNormalView.getView(), sampler)
                    .imageSampler(5, blockAtlasSpecularView.getView(), sampler)
                    .imageStore(6, composite0mainView.getView(()->outImg));
            updater.apply();

            updater = new DescriptorUpdateBuilder(ctx, customTextureViews.length, placeholderImageView)
                    .set(ctexSet);

            for (int i = 0; i < customTextureViews.length; i++) {
                updater.imageSampler(i, customTextureViews[i].getView(), ctexSampler);
            }
            updater.apply();

            updater = new DescriptorUpdateBuilder(ctx, ssbos.length, placeholderImageView)
                    .set(ssboSet);

            for (ShaderStorageBuffer ssbo : ssbos) {
                updater.buffer(ssbo.getIndex(), ((IVGBuffer) ssbo).getBuffer());
            }
            updater.apply();

            //TODO: dont use a single use pool for commands like this...
            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            try (var stack = stackPush()) {
                var barriers = VkImageMemoryBarrier.calloc(4 + customTextureViews.length, stack);
                applyImageBarrier(barriers.get(), composite0mainView.getImage(), VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT);
                applyImageBarrier(barriers.get(), blockAtlasView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT);
                var image = blockAtlasNormalView.getImage();
                if (image != null) applyImageBarrier(barriers.get(), image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT);
                image = blockAtlasSpecularView.getImage();
                if (image != null) applyImageBarrier(barriers.get(), image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT);

                for(SharedImageViewTracker customtexView : customTextureViews) {
                   applyImageBarrier(barriers.get(), customtexView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT);
                }

                barriers.limit(barriers.position());
                barriers.rewind();
                vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, null, null, barriers);
            }

            for (var pipeline : raytracePipelines) {
                pipeline.bind(cmd);
                pipeline.bindDSet(cmd, commonSet, accelerationManager.getGeometrySet(), ctexSet, ssboSet);
                pipeline.trace(cmd, outImg.width, outImg.height, 1);
            }

            cmd.end();
            var fence = ctx.sync.createFence();
            ctx.cmd.submit(0, new VCmdBuff[]{cmd}, new VSemaphore[]{tlasLink}, new int[]{VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR}, new VSemaphore[]{out}, fence);


            var semCapture = previousSemaphore;
            previousSemaphore = out;
            ctx.sync.addCallback(fence, ()->{
                tlasLink.free();
                in.free();
                cmd.enqueueFree();
                fence.free();

                uboBuffer.free();
                if (semCapture != null) {
                    semCapture.free();
                }
            });
        }

        out.glWait(new int[0], new int[]{outImg.glId}, new int[]{GL_LAYOUT_GENERAL_EXT});
        glFlush();

        fidx++;
        fidx %= 10;

    }

    public void destory() {
        for (var pass : raytracePipelines) {
            pass.free();
        }
        commonLayout.free();
        customtexLayout.free();
        storageBufferLayout.free();
        commonDescriptorPool.free();
        customtexDescriptorPool.free();
        storageBufferDescriptorPool.free();
        ctx.sync.checkFences();
        singleUsePool.doReleases();
        singleUsePool.free();
        if (previousSemaphore != null) {
            previousSemaphore.free();
        }

        for (SharedImageViewTracker customTexView : customTextureViews) {
            customTexView.free();
        }

        composite0mainView.free();
        blockAtlasView.free();
        blockAtlasNormalView.free();
        blockAtlasSpecularView.free();
        placeholderImageView.free();
        placeholderImage.free();
        sampler.free();
        ctexSampler.free();
    }


}
