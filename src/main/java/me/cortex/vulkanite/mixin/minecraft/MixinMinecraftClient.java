package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.Vulkanite;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftClient {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/Minecraft");
    @Unique
    private static int frameCount;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void onRenderFrameStart(boolean tick, CallbackInfo ci) {
        Vulkanite.INSTANCE.renderTick();
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void onRenderFrameEnd(boolean tick, CallbackInfo ci) {
        Vulkanite.INSTANCE.fenceTick();
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;shutdownRenderer()V"))
    private void onClose(CallbackInfo ci) {
        LOGGER.info("Minecraft closing — destroying Vulkanite");
        Vulkanite.INSTANCE.destroy();
    }
}
