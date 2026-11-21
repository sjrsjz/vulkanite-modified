package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/** Builds only meshes accepted by Sodium; the render thread validates completed results again. */
public class AccelerationBlasBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/BlasBuilder");
    private static final int MAX_BATCH_SIZE = 64;
    private final VContext context;
    private final int asyncQueue;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final Predicate<JobPassThroughData> isCurrent;
    private final VCommandPool pool;
    private final LinkedBlockingQueue<BLASBuildJob> jobs = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean running = true;

    private record TriangleData(int quadCount, NativeBuffer positions, NativeBuffer vertices, int flags) {}
    private record BLASBuildJob(List<TriangleData> geometries, RenderSection section, long version) {
        void free() {
            geometries.forEach(data -> { data.positions().free(); data.vertices().free(); });
        }
    }
    public record BLASBuildResult(VAccelerationStructure structure, JobPassThroughData data) {}
    public record BLASBatchResult(List<BLASBuildResult> results, VSemaphore semaphore) {}

    public AccelerationBlasBuilder(VContext context, int asyncQueue, Consumer<BLASBatchResult> resultConsumer,
            Predicate<JobPassThroughData> isCurrent) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.resultConsumer = resultConsumer;
        this.isCurrent = isCurrent;
        this.pool = context.cmd.createSingleUsePool();
        this.worker = new Thread(this::run, "Acceleration blas worker");
        this.worker.start();
    }

    public void enqueue(ChunkBuildOutput output, long version) {
        var result = (IAccelerationBuildResult) output;
        if (!running || context.sync.isDeviceLost()) {
            result.releaseAccelerationGeometry();
            return;
        }
        var geometries = new ArrayList<TriangleData>();
        var buffers = result.getGeometryBuffersData();
        for (var entry : result.getAccelerationGeometryData().entrySet()) {
            var vertices = buffers.get(entry.getKey());
            if (vertices == null || vertices.getLength() == 0) {
                throw new IllegalStateException("Missing RT vertices for " + entry.getKey());
            }
            int flags = entry.getKey() == DefaultTerrainRenderPasses.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
            geometries.add(new TriangleData(entry.getValue().quadCount(), entry.getValue().data(), vertices, flags));
        }
        result.setAccelerationGeometryData(null);
        result.setGeometryBuffersData(null);
        jobs.add(new BLASBuildJob(geometries, output.section, version));
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void run() {
        var batch = new ArrayList<BLASBuildJob>(MAX_BATCH_SIZE);
        try {
            while (running && !context.sync.isDeviceLost()) {
                try {
                    batch.add(jobs.take());
                } catch (InterruptedException e) {
                    break;
                }
                jobs.drainTo(batch, MAX_BATCH_SIZE - 1);
                batch.removeIf(job -> {
                    if (!running || !isCurrent.test(new JobPassThroughData(job.section(), job.version(), List.of()))) {
                        job.free();
                        return true;
                    }
                    return false;
                });
                try {
                    if (!batch.isEmpty()) build(batch);
                } finally {
                    batch.forEach(BLASBuildJob::free);
                    batch.clear();
                }
            }
        } catch (RuntimeException | AssertionError e) {
            context.sync.fail("BLAS worker failed", e);
            LOGGER.error("Stopped BLAS worker", e);
        } finally {
            BLASBuildJob queued;
            while ((queued = jobs.poll()) != null) queued.free();
            pool.doReleases();
            pool.free();
        }
    }

    private void build(List<BLASBuildJob> batch) {
        List<VBuffer> temporary = new ArrayList<>();
        List<VBuffer> retained = new ArrayList<>();
        List<VAccelerationStructure> structures = new ArrayList<>();
        List<BLASBuildResult> results = new ArrayList<>();
        VCmdBuff cmd = null;
        VFence fence = null;
        VSemaphore ready = null;
        boolean submitted = false;
        boolean published = false;
        try (var stack = MemoryStack.create(1 << 20).push()) {
            var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(batch.size(), stack);
            var ranges = stack.mallocPointer(batch.size());
            cmd = pool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            int index = 0;
            for (var job : batch) {
                var geometry = VkAccelerationStructureGeometryKHR.calloc(job.geometries().size(), stack);
                var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(job.geometries().size(), stack);
                var maxPrimitives = stack.mallocInt(job.geometries().size());
                ranges.put(range);
                var vertexBuffers = new ArrayList<VBuffer>();
                for (int g = 0; g < job.geometries().size(); g++) {
                    var data = job.geometries().get(g);
                    var vertices = context.memory.createBuffer(data.vertices().getLength(),
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    retained.add(vertices);
                    vertexBuffers.add(vertices);
                    cmd.encodeDataUpload(context.memory, MemoryUtil.memAddress(data.vertices().getDirectBuffer()),
                            vertices, 0, data.vertices().getLength());
                    var positions = context.memory.createBuffer(data.positions().getLength(),
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    temporary.add(positions);
                    cmd.encodeDataUpload(context.memory, MemoryUtil.memAddress(data.positions().getDirectBuffer()),
                            positions, 0, data.positions().getLength());
                    long indices = SharedQuadVkIndexBuffer.getAddress(context, Math.max(data.quadCount(), 120000));
                    geometry.get(g).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(data.flags())
                            .geometry(t -> t.triangles(tri -> tri.sType$Default()
                                    .vertexData(a -> a.deviceAddress(positions.deviceAddress()))
                                    .vertexFormat(VK_FORMAT_R16G16B16A16_SFLOAT).vertexStride(8)
                                    .maxVertex(data.quadCount() * 4 - 1)
                                    .indexData(a -> a.deviceAddress(indices)).indexType(SharedQuadVkIndexBuffer.TYPE)));
                    range.get(g).primitiveCount(data.quadCount() * 2);
                    maxPrimitives.put(data.quadCount() * 2);
                    cmd.encodeBufferBarrier(positions, 0, VK_WHOLE_SIZE,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
                }
                maxPrimitives.flip();
                var info = infos.get(index++).sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .pGeometries(geometry).geometryCount(job.geometries().size());
                var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(context.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        info, maxPrimitives, sizes);
                var structure = context.memory.createAcceleration(sizes.accelerationStructureSize(), 256,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                structures.add(structure);
                var scratch = context.memory.createBuffer(Math.max(1, sizes.buildScratchSize()),
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, context.properties.scratchAlignment, 0);
                temporary.add(scratch);
                info.dstAccelerationStructure(structure.structure).scratchData(a -> a.deviceAddress(scratch.deviceAddress()));
                results.add(new BLASBuildResult(structure,
                        new JobPassThroughData(job.section(), job.version(), vertexBuffers)));
            }
            ranges.flip();
            vkCmdBuildAccelerationStructuresKHR(cmd.buffer, infos, ranges);
            cmd.end();
            ready = context.sync.createBinarySemaphore();
            fence = context.sync.createFence();
            context.cmd.submit(asyncQueue, new VCmdBuff[]{cmd}, new VSemaphore[0], new int[0],
                    new VSemaphore[]{ready}, fence);
            submitted = true;
            context.sync.waitForFence(fence);
            resultConsumer.accept(new BLASBatchResult(results, ready));
            published = true;
        } finally {
            if (submitted && !published) context.cmd.waitQueueIdle(asyncQueue);
            if (fence != null) fence.free();
            if (cmd != null) pool.releaseNow(cmd);
            temporary.forEach(VBuffer::free);
            if (!published) {
                retained.forEach(VBuffer::free);
                structures.forEach(VAccelerationStructure::free);
                if (ready != null) ready.free();
            }
        }
    }
}
