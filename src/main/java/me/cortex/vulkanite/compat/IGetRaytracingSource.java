package me.cortex.vulkanite.compat;

public interface IGetRaytracingSource {
    RaytracingShaderSource[] getRaytracingSource();
    boolean usesTerrainLightBvh();
}
