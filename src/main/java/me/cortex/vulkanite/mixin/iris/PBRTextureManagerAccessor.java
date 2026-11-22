package me.cortex.vulkanite.mixin.iris;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = PBRTextureManager.class, remap = false)
public interface PBRTextureManagerAccessor {
    @Accessor("holders")
    Map<GpuTexture, PBRTextureHolder> vulkanite$getLoadedTextures();
}
