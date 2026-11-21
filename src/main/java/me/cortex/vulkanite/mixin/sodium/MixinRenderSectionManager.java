package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.RaytracingPackState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Inject(method = "updateWithResult", at = @At("RETURN"))
    private void vulkanite$acceptMesh(Viewport viewport, RenderSection section, ChunkBuildOutput result,
            List<RenderSection> patches, CallbackInfoReturnable<Integer> cir) {
        if (RaytracingPackState.isActive() && !section.isDisposed()) {
            Vulkanite.INSTANCE.upload(List.of(result));
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void vulkanite$invalidateWorld(CallbackInfo ci) {
        Vulkanite.INSTANCE.clearWorld();
    }
}
