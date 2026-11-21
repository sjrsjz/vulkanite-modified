package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryFrame;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

public final class AccelerationManager implements AutoCloseable {
    private static final int BUILDS_PER_FRAME = 32;
    private final VContext context;
    private final AccelerationBlasBuilder builder;
    private final AccelerationTLASManager tlas;
    private final SectionBuildState<RenderSection> versions = new SectionBuildState<>();
    private final Map<RenderSection, PendingMesh> pending = new LinkedHashMap<>();
    private EntityGeometryFrame entityFrame;

    public AccelerationManager(VContext context) {
        this.context = context;
        builder = new AccelerationBlasBuilder(context);
        tlas = new AccelerationTLASManager(context);
    }

    private record PendingMesh(long version, List<AccelerationBlasBuilder.Geometry> geometries) {
        void free() {
            geometries.forEach(AccelerationBlasBuilder.Geometry::free);
        }
    }

    // Sodium publishes accepted build outputs on the render thread.
    public void accept(ChunkBuildOutput output) {
        var data = (IAccelerationBuildResult) output;
        if (output.section.isDisposed()) {
            data.releaseAccelerationGeometry();
            return;
        }

        RenderSection section = output.section;
        long version = versions.accept(section);
        discardPending(section);
        var positions = data.getAccelerationGeometryData();
        if (positions == null || positions.isEmpty()) {
            tlas.removeSection(section);
            data.releaseAccelerationGeometry();
            return;
        }

        var attributes = data.getGeometryBuffersData();
        var geometries = new ArrayList<AccelerationBlasBuilder.Geometry>(positions.size());
        for (var entry : positions.entrySet()) {
            var vertices = entry.getValue();
            int flags = entry.getKey() == DefaultTerrainRenderPasses.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
            geometries.add(new AccelerationBlasBuilder.Geometry(vertices.quadCount(), vertices.data(),
                    attributes.get(entry.getKey()), flags));
        }
        pending.put(section, new PendingMesh(version, geometries));
        data.setAccelerationGeometryData(null);
        data.setGeometryBuffersData(null);
    }

    public VAccelerationStructure recordScene(VCmdBuff command) {
        var jobs = pending.entrySet().iterator();
        for (int count = 0; count < BUILDS_PER_FRAME && jobs.hasNext(); count++) {
            var entry = jobs.next();
            RenderSection section = entry.getKey();
            PendingMesh mesh = entry.getValue();
            jobs.remove();
            try {
                if (!section.isDisposed() && versions.isCurrent(section, mesh.version())) {
                    var built = builder.build(command, mesh.geometries(), VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
                    tlas.updateSection(section, built);
                }
            } finally {
                mesh.free();
            }
        }

        EntityGeometryFrame frame = entityFrame;
        entityFrame = null;
        if (frame == null) {
            tlas.clearEntity();
        } else {
            try {
                buildEntity(command, frame);
            } finally {
                frame.free();
            }
        }

        return tlas.build(command);
    }

    private void buildEntity(VCmdBuff command, EntityGeometryFrame frame) {
        var geometry = new AccelerationBlasBuilder.Geometry(frame.quadCount(), frame.vertices(), frame.geometry(), 0);
        var mesh = builder.build(command, List.of(geometry), VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR);
        VBuffer motion = null;
        try {
            long size = frame.motion().getLength();
            motion = context.memory.createBuffer(size,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            command.upload(context, org.lwjgl.system.MemoryUtil.memAddress(frame.motion().getDirectBuffer()), motion, size);
        } catch (RuntimeException | Error failure) {
            context.retire(mesh::free);
            if (motion != null) context.retire(motion::free);
            throw failure;
        }
        tlas.updateEntity(mesh, motion, frame.originX(), frame.originY(), frame.originZ());
    }

    public void setEntityGeometry(EntityGeometryFrame geometry) {
        if (entityFrame != null) entityFrame.free();
        entityFrame = geometry;
    }

    private void discardPending(RenderSection section) {
        var mesh = pending.remove(section);
        if (mesh != null) mesh.free();
    }

    public void sectionRemove(RenderSection section) {
        versions.remove(section);
        discardPending(section);
        tlas.removeSection(section);
    }

    public void clearWorld() {
        versions.clear();
        pending.values().forEach(PendingMesh::free);
        pending.clear();
        setEntityGeometry(null);
        tlas.clear();
    }

    @Override
    public void close() {
        clearWorld();
        tlas.close();
    }

    public long getGeometrySet() { return tlas.getGeometrySet(); }
    public VDescriptorSetLayout getGeometryLayout() { return tlas.getGeometryLayout(); }
    public VBuffer getEntityMotionBuffer() { return tlas.getEntityMotionBuffer(); }
    public int getTerrainSectionCount() { return tlas.getTerrainSectionCount(); }
    public Set<SectionPos> getTerrainSections() { return tlas.getTerrainSections(); }
}
