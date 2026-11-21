package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.Vulkanite;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftClient {
    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;shutdownRenderer()V"))
    private void vulkanite$close(CallbackInfo ci) {
        Vulkanite.INSTANCE.destroy();
    }
}
