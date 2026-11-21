package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * MC 26.2: AbstractTexture no longer has glId/getGlId/clearGlId.
 * Instead uses GpuTexture from RenderSystem.getDevice().
 * We add IVGImage support for Vulkan texture sharing.
 */
@Mixin(AbstractTexture.class)
public class MixinAbstractTexture implements IVGImage {
    @org.spongepowered.asm.mixin.Shadow protected com.mojang.blaze3d.textures.GpuTexture texture;
    @org.spongepowered.asm.mixin.Shadow protected com.mojang.blaze3d.textures.GpuTextureView textureView;
    @Unique
    private Optional<VGImage> vgImage = Optional.empty();

    @Unique
    private void ensureVGImage() {
        if (vgImage == null) {
            vgImage = Optional.empty();
        }
    }

    @Override
    public void setVGImage(VGImage image) {
        this.vgImage = Optional.of(image);
    }

    @Override
    public Optional<VGImage> getVGImage() {
        ensureVGImage();
        return vgImage;
    }

    @Override
    public void ensureShared() {
        ensureVGImage();
        if (vgImage.isPresent() || texture == null || !me.cortex.vulkanite.compat.RaytracingPackState.isActive()) return;
        if (texture.getFormat() != com.mojang.blaze3d.GpuFormat.RGBA8_UNORM || texture.getDepthOrLayers() != 1) return;
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        VGImage shared = Vulkanite.INSTANCE.getCtx().memory.createSharedImage(texture.getWidth(0), texture.getHeight(0),
                1, texture.getMipLevels(), org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,
                org.lwjgl.opengl.GL11C.GL_RGBA8,
                org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        try {
            var replacement = me.cortex.vulkanite.compat.GlTextureReflection.wrapSharedTexture(device, shared.glId,
                    texture.getLabel(), texture.usage(), texture.getFormat(), texture.getWidth(0), texture.getHeight(0),
                    1, texture.getMipLevels()).orElseThrow(() -> new IllegalStateException("Cannot wrap shared texture"));
            for (int mip = 0; mip < texture.getMipLevels(); mip++) {
                org.lwjgl.opengl.GL43C.glCopyImageSubData(texture.iris$getGlId(), org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,
                        mip, 0, 0, 0, shared.glId, org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, mip, 0, 0, 0,
                        Math.max(1, texture.getWidth(mip)), Math.max(1, texture.getHeight(mip)), 1);
            }
            if (textureView != null) textureView.close();
            texture.close();
            texture = replacement;
            textureView = device.createTextureView(replacement);
            shared.transferGlTextureOwnership();
            vgImage = Optional.of(shared);
        } catch (RuntimeException | AssertionError e) {
            shared.free();
            throw e;
        }
    }

    @Inject(method = "releaseTextures", at = @At("HEAD"))
    private void onRelease(CallbackInfo ci) {
        ensureVGImage();
        if (vgImage.isPresent()) {
            Vulkanite.INSTANCE.addSyncedCallback(vgImage.get()::free);
            vgImage = Optional.empty();
        }
    }
}
