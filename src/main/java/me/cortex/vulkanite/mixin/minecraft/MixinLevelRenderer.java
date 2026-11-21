package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.irisshaders.iris.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void vulkanite$beginEntityCapture(LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector, boolean renderOutline, CallbackInfo ci) {
        if (!EntityGeometryCollector.INSTANCE.isRayTracingActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack handView = new PoseStack();
        GameRendererAccessor gameRenderer = (GameRendererAccessor) minecraft.gameRenderer;
        gameRenderer.invokeBobHurt(levelRenderState.cameraRenderState, handView);
        if (minecraft.options.bobView().get()) {
            gameRenderer.invokeBobView(levelRenderState.cameraRenderState, handView);
        }
        EntityGeometryCollector.INSTANCE.beginFrame(levelRenderState.cameraRenderState.pos,
                levelRenderState.cameraRenderState.viewRotationMatrix, handView.last().pose());
    }
}
