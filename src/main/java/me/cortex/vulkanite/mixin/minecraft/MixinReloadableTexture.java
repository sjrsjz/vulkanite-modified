package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.GlTextureReflection;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(ReloadableTexture.class)
public abstract class MixinReloadableTexture extends AbstractTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/ReloadableTexture");

    @Redirect(method = "doLoad", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", remap = false))
    private GpuTexture vulkanite$makeReloadableTextureShared(GpuDevice device, Supplier<String> label, int usage,
            GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        if (!Vulkanite.IS_ENABLED || !me.cortex.vulkanite.compat.RaytracingPackState.isActive()) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }

        Identifier location = ((ReloadableTexture) (Object) this).resourceId();
        if (!location.getPath().startsWith("textures/entity/")) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }

        VGImage image = null;
        try {
            image = Vulkanite.INSTANCE.getCtx().memory.createSharedImage(width, height, 1, mipLevels,
                    VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            String textureLabel = label != null ? label.get() : "reloadable-texture";
            GpuTexture texture = GlTextureReflection.wrapSharedTexture(device, image.glId, textureLabel,
                    usage, format, width, height, depthOrLayers, mipLevels)
                    .orElseThrow(() -> new IllegalStateException("Failed to wrap shared reloadable texture"));
            image.transferGlTextureOwnership();
            ((IVGImage) this).setVGImage(image);
            return texture;
        } catch (Exception | AssertionError e) {
            if (image != null) {
                image.free();
            }
            LOGGER.error("Failed to share reloadable texture with Vulkan", e);
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
    }
}
