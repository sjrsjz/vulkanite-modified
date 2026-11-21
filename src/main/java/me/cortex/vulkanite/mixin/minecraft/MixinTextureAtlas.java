package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.GlTextureReflection;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Makes the block atlas (and any TextureAtlas) Vulkan-shared from creation.
 * Redirects the device.createTexture() call inside TextureAtlas.createTexture()
 * to use external-memory-backed GL storage so the same GL texture id is also a
 * valid Vulkan image — no per-frame copy needed.
 */
@Mixin(value = TextureAtlas.class)
public abstract class MixinTextureAtlas extends AbstractTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/TextureAtlas");

    @Redirect(method = "createTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", remap = false))
    private GpuTexture vulkanite$makeAtlasShared(GpuDevice device, Supplier<String> label, int usage,
            GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels) {
        if (!me.cortex.vulkanite.compat.RaytracingPackState.isActive()) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        LOGGER.info("Redirecting TextureAtlas.createTexture to Vulkan-shared path");
        if (Vulkanite.INSTANCE == null) {
            LOGGER.warn("Vulkanite instance is null, falling back to plain GL for PBR atlas");
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        if (!Vulkanite.IS_ENABLED) {
            LOGGER.warn("Vulkanite is disabled, falling back to plain GL for PBR atlas");
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        VGImage vg = null;
        try {
            var ctx = Vulkanite.INSTANCE.getCtx();
            // GL_RGBA8 / VK_FORMAT_R8G8B8A8_UNORM matches GpuFormat.RGBA8_UNORM, which both
            // TextureAtlas and PBRAtlasTexture always use (hardcoded in their createTexture
            // calls).
            vg = ctx.memory.createSharedImage(width, height, 1, mipLevels,
                    VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            // Wrap the shared GL id in a real GlTexture via reflection (see
            // GlTextureReflection
            // doc comment for why this can't be a mixin bridge).
            String labelStr = label != null ? label.get() : "atlas";

            GpuTexture gpuTex = GlTextureReflection.wrapSharedTexture(
                    device, vg.glId, labelStr, usage, format, width, height, depthOrLayers, mipLevels)
                    .orElseThrow(() -> new RuntimeException("Failed to wrap shared texture for block atlas"));

            vg.transferGlTextureOwnership();
            // Store VGImage on AbstractTexture via MixinAbstractTexture (auto-freed on
            // releaseTextures).
            ((IVGImage) this).setVGImage(vg);
            LOGGER.info("Block atlas {}x{} mips={} made Vulkan-shared (glId={})",
                    width, height, mipLevels, vg.glId);
            return gpuTex;
        } catch (Exception e) {
            if (vg != null) {
                vg.free();
            }
            LOGGER.error("Failed to make atlas Vulkan-shared, falling back to plain GL", e);
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
    }
}
