package me.cortex.vulkanite.smoke.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class HiddenWindowMixin {
    @Shadow @Final private long handle;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hideTestWindow(CallbackInfo ci) {
        org.lwjgl.glfw.GLFW.glfwHideWindow(handle);
    }
}
