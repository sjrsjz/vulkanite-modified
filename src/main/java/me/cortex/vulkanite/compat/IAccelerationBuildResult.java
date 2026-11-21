package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

import java.util.Map;

public interface IAccelerationBuildResult {
    void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map);
    Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData();
    void setGeometryBuffersData(Map<TerrainRenderPass, NativeBuffer> map);
    Map<TerrainRenderPass, NativeBuffer> getGeometryBuffersData();
    ChunkVertexType getVertexFormat();
    void setVertexFormat(ChunkVertexType format);

    default void releaseAccelerationGeometry() {
        var geometry = getAccelerationGeometryData();
        if (geometry != null) {
            geometry.values().forEach(data -> data.data().free());
            setAccelerationGeometryData(null);
        }
        var buffers = getGeometryBuffersData();
        if (buffers != null) {
            buffers.values().forEach(NativeBuffer::free);
            setGeometryBuffersData(null);
        }
    }
}
