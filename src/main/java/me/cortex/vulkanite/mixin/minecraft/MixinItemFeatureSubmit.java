package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import me.cortex.vulkanite.client.rendering.entity.EntityOwnedSubmissionExtension;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.Submit.class)
public class MixinItemFeatureSubmit implements EntityOwnedSubmissionExtension {
    @Unique
    private long vulkanite$ownerKey = -1L;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanite$captureEntityOwner(CallbackInfo ci) {
        vulkanite$ownerKey = EntityGeometryCollector.INSTANCE.currentSubmissionOwner();
    }

    @Override
    public long vulkanite$getOwnerKey() {
        return vulkanite$ownerKey;
    }

    @Override
    public void vulkanite$setOwnerKey(long ownerKey) {
        vulkanite$ownerKey = ownerKey;
    }
}
