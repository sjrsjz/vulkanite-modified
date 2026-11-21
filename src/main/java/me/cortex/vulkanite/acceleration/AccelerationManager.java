package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryFrame;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private final VContext ctx;
    private final AccelerationTLASManager tlasManager;
    private final AccelerationBlasBuilder blasBuilder;
    private final List<VSemaphore> syncs = new LinkedList<>();
    // BLAS results queued by background worker thread, drained on render thread in updateTick()
    private final ConcurrentLinkedDeque<AccelerationBlasBuilder.BLASBatchResult> pendingResults = new ConcurrentLinkedDeque<>();
    private EntityGeometryFrame pendingEntityGeometry;
    private final SectionBuildState<RenderSection> sections = new SectionBuildState<>();
    private boolean closed;

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.tlasManager = new AccelerationTLASManager(context, 0);
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, pendingResults::add,
                data -> !data.section().isDisposed() && sections.isCurrent(data.section(), data.time()));
    }

    // Called only after Sodium accepts a mesh on the render thread.
    public void chunkBuilds(List<ChunkBuildOutput> results) {
        for (var result : results) {
            if (closed || ctx.sync.isDeviceLost() || result.section.isDisposed()) {
                ((me.cortex.vulkanite.compat.IAccelerationBuildResult) result).releaseAccelerationGeometry();
                continue;
            }
            long version = sections.accept(result.section);
            if (((me.cortex.vulkanite.compat.IAccelerationBuildResult) result).getAccelerationGeometryData() == null) {
                tlasManager.removeSection(result.section);
            } else {
                blasBuilder.enqueue(result, version);
            }
        }
    }

    // Called on render thread — drains BLAS results and updates TLAS data structures
    public void updateTick() {
        while (!pendingResults.isEmpty()) {
            var batch = pendingResults.poll();
            for (var result : batch.results()) {
                var data = result.data();
                if (!closed && !data.section().isDisposed() && sections.isCurrent(data.section(), data.time())) {
                    tlasManager.updateSections(List.of(result));
                } else {
                    result.structure().free();
                    data.geometryBuffers().forEach(VBuffer::free);
                }
            }
            syncs.add(batch.semaphore());
        }
    }

    public VAccelerationStructure buildTLAS(VSemaphore inLink, VSemaphore outLink) {
        EntityGeometryFrame entityGeometry;
        synchronized (this) {
            entityGeometry = pendingEntityGeometry;
            pendingEntityGeometry = null;
        }
        tlasManager.buildTLAS(inLink, outLink, syncs.toArray(new VSemaphore[0]), entityGeometry);
        syncs.clear();
        return tlasManager.getTlas();
    }

    public synchronized void setEntityGeometry(EntityGeometryFrame geometry) {
        if (pendingEntityGeometry != null) {
            pendingEntityGeometry.free();
        }
        pendingEntityGeometry = geometry;
    }

    public void sectionRemove(RenderSection section) {
        sections.remove(section);
        tlasManager.removeSection(section);
    }

    public void clearWorld() {
        sections.clear();
        synchronized (this) {
            if (pendingEntityGeometry != null) {
                pendingEntityGeometry.free();
                pendingEntityGeometry = null;
            }
        }
        tlasManager.clearSections();
        updateTick();
    }

    public void cleanup() {
        if (closed) return;
        closed = true;
        sections.clear();
        blasBuilder.shutdown();
        ctx.cmd.waitQueueIdle(0);
        ctx.cmd.waitQueueIdle(1);
        updateTick();
        syncs.forEach(VSemaphore::free);
        syncs.clear();
        synchronized (this) {
            if (pendingEntityGeometry != null) {
                pendingEntityGeometry.free();
                pendingEntityGeometry = null;
            }
        }
        tlasManager.cleanupTick();
    }

    public long getGeometrySet() { return tlasManager.getGeometrySet(); }
    public int getTerrainSectionCount() { return tlasManager.getTerrainSectionCount(); }
    public java.util.Set<net.minecraft.core.SectionPos> getTerrainSections() { return tlasManager.getTerrainSections(); }
    public VDescriptorSetLayout getGeometryLayout() { return tlasManager.getGeometryLayout(); }
    public VBuffer getEntityMotionBuffer() { return tlasManager.getEntityMotionBuffer(); }
}
