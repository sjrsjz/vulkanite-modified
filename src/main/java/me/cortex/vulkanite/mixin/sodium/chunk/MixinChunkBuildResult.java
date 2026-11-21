package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.GeometryData;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildResult implements IAccelerationBuildResult {
    @Unique private Map<TerrainRenderPass, GeometryData> geometryMap;
    @Unique private Map<TerrainRenderPass, NativeBuffer> geomBuffersMap;
    @Unique private ChunkVertexType vertexType;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void vulkanite$releaseUnsubmittedGeometry(CallbackInfo ci) {
        releaseAccelerationGeometry();
    }

    @Override
    public void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map) {
        this.geometryMap = map;
    }

    @Override
    public Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData() {
        return geometryMap;
    }

    @Override
    public void setGeometryBuffersData(Map<TerrainRenderPass, NativeBuffer> map) {
        this.geomBuffersMap = map;
    }

    @Override
    public Map<TerrainRenderPass, NativeBuffer> getGeometryBuffersData() {
        return geomBuffersMap;
    }

    @Override
    public ChunkVertexType getVertexFormat() {
        return vertexType;
    }

    @Override
    public void setVertexFormat(ChunkVertexType format) {
        vertexType = format;
    }
}
