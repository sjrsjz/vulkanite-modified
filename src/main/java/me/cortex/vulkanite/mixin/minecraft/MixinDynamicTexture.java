package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.GlTextureReflection;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(DynamicTexture.class)
public abstract class MixinDynamicTexture extends AbstractTexture {
    @Redirect(method = "createTexture(Ljava/util/function/Supplier;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", remap = false))
    private GpuTexture vulkanite$shareDownloadedSkin(GpuDevice device, Supplier<String> label, int usage,
            GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        String labelText = label != null ? label.get() : null;
        if (!shouldShareWithVulkan(labelText)) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        return createSharedTexture(device, labelText, usage, format, width, height, depthOrLayers, mipLevels,
                () -> device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels));
    }

    @Redirect(method = "createTexture(Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", remap = false))
    private GpuTexture vulkanite$shareNamedPlayerTexture(GpuDevice device, String label, int usage,
            GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        if (!shouldShareWithVulkan(label)) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        return createSharedTexture(device, label, usage, format, width, height, depthOrLayers, mipLevels,
                () -> device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels));
    }

    private GpuTexture createSharedTexture(GpuDevice device, String label, int usage, GpuFormat format,
            int width, int height, int depthOrLayers, int mipLevels, Supplier<GpuTexture> fallback) {
        if (!Vulkanite.IS_ENABLED || !me.cortex.vulkanite.compat.RaytracingPackState.isActive()) {
            return fallback.get();
        }

        VGImage image = null;
        try {
            image = Vulkanite.INSTANCE.getCtx().memory.createSharedImage(width, height, 1, mipLevels,
                    VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            GpuTexture texture = GlTextureReflection.wrapSharedTexture(device, image.glId, label,
                    usage, format, width, height, depthOrLayers, mipLevels)
                    .orElseThrow(() -> new IllegalStateException("Failed to wrap shared player texture"));
            image.transferGlTextureOwnership();
            ((IVGImage) this).setVGImage(image);
            return texture;
        } catch (Exception | AssertionError e) {
            if (image != null) {
                image.free();
            }
            return fallback.get();
        }
    }

    private boolean shouldShareWithVulkan(String label) {
        // Iris PNG custom textures are DynamicTexture subclasses. They must be
        // backed by a shared image before VulkanPipeline builds set 2.
        return isPlayerTexture(label) || ((Object) this).getClass().getName()
                .equals("net.irisshaders.iris.targets.backed.NativeImageBackedCustomTexture");
    }

    private static boolean isPlayerTexture(String label) {
        if (label == null) {
            return false;
        }
        return label.startsWith("minecraft:skins/")
                || label.startsWith("minecraft:capes/")
                || label.startsWith("minecraft:elytra/");
    }
}
