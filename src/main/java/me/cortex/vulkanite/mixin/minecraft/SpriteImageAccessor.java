package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteImageAccessor {
    @Accessor("originalImage")
    NativeImage vulkanite$getOriginalImage();
}
