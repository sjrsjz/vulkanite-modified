package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4x3f;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

final class AccelerationTLASManager implements AutoCloseable {
    private static final int ENTITY_INSTANCE_FLAG = 0x800000;
    private static final int RT_STAGES = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR
            | VK_SHADER_STAGE_INTERSECTION_BIT_KHR | VK_SHADER_STAGE_CALLABLE_BIT_KHR;

    private final VContext context;
    private final Map<RenderSection, Instance> terrain = new IdentityHashMap<>();
    private final BitSet geometrySlots = new BitSet();
    private final VDescriptorSetLayout geometryLayout;
    private VDescriptorPool geometryPool;
    private int descriptorCapacity;
    private Instance entity;
    private VBuffer entityMotion;
    private VAccelerationStructure activeTlas;

    AccelerationTLASManager(VContext context) {
        this.context = context;
        geometryLayout = new DescriptorSetLayoutBuilder(VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                .binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, context.properties.geometryCapacity, RT_STAGES)
                .setBindingFlags(0, VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                        | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
                        | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT)
                .build(context);
        try {
            ensureDescriptors(1);
        } catch (RuntimeException | Error failure) {
            geometryLayout.free();
            throw failure;
        }
    }

    private record Instance(AccelerationBlasBuilder.Mesh mesh, int slot, float x, float y, float z) {}

    int updateSection(RenderSection section, AccelerationBlasBuilder.Mesh mesh) {
        var instance = createInstance(mesh, section.getOriginX(), section.getOriginY(), section.getOriginZ());
        var previous = terrain.put(section, instance);
        if (previous != null) retire(previous);
        return instance.slot();
    }

    void removeSection(RenderSection section) {
        var removed = terrain.remove(section);
        if (removed != null) retire(removed);
    }

    void updateEntity(AccelerationBlasBuilder.Mesh mesh, VBuffer motion, float x, float y, float z) {
        Instance replacement;
        try {
            replacement = createInstance(mesh, x, y, z);
        } catch (RuntimeException | Error failure) {
            context.retire(motion::free);
            throw failure;
        }
        clearEntity();
        entity = replacement;
        entityMotion = motion;
    }

    void clearEntity() {
        if (entity == null) return;
        retire(entity);
        context.retire(entityMotion::free);
        entity = null;
        entityMotion = null;
    }

    private Instance createInstance(AccelerationBlasBuilder.Mesh mesh, float x, float y, float z) {
        int count = mesh.attributes().size();
        int slot = geometrySlots.nextClearBit(0);
        int used;
        while ((used = geometrySlots.nextSetBit(slot)) >= 0 && used < slot + count) {
            slot = geometrySlots.nextClearBit(used);
        }
        try {
            ensureDescriptors(slot + count);
            try (var update = new DescriptorUpdateBuilder(context, 1)) {
                update.set(getGeometrySet()).buffer(0, slot, mesh.attributes()).apply();
            }
        } catch (RuntimeException | Error failure) {
            context.retire(mesh::free);
            throw failure;
        }
        geometrySlots.set(slot, slot + count);
        return new Instance(mesh, slot, x, y, z);
    }

    private void retire(Instance instance) {
        context.retire(() -> {
            instance.mesh().free();
            geometrySlots.clear(instance.slot(), instance.slot() + instance.mesh().attributes().size());
        });
    }

    private void ensureDescriptors(int required) {
        if (required <= descriptorCapacity) return;
        int limit = context.properties.geometryCapacity;
        if (required > limit) throw new IllegalStateException("Vulkanite geometry descriptor capacity exhausted (" + limit + ")");
        int capacity = Math.min(limit, Math.max(32, Integer.highestOneBit(required - 1) << 1));
        var pool = new VDescriptorPool(context, VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT,
                1, capacity, geometryLayout.types);
        try {
            pool.allocateSets(geometryLayout, capacity);
            if (geometryPool != null) {
                try (var stack = stackPush()) {
                    var copy = VkCopyDescriptorSet.calloc(1, stack).sType$Default().srcSet(getGeometrySet())
                            .dstSet(pool.get(0)).descriptorCount(descriptorCapacity);
                    vkUpdateDescriptorSets(context.device, null, copy);
                }
                context.retire(geometryPool::free);
            }
            geometryPool = pool;
            descriptorCapacity = capacity;
        } catch (RuntimeException | Error failure) {
            pool.free();
            throw failure;
        }
    }

    VAccelerationStructure build(VCmdBuff command) {
        int count = terrain.size() + (entity == null ? 0 : 1);
        VAccelerationStructure result = null;
        try (var stack = stackPush()) {
            long dataAddress = count == 0 ? 0 : writeInstances(count);
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(dataAddress);
            var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(geometry).geometryCount(1);
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(context.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(count), sizes);
            result = context.memory.createAcceleration(sizes.accelerationStructureSize(), 256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            var scratch = context.memory.createBuffer(Math.max(1, sizes.buildScratchSize()),
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, context.properties.scratchAlignment, 0);
            context.retire(scratch::free);
            build.dstAccelerationStructure(result.structure).scratchData(address -> address.deviceAddress(scratch.deviceAddress()));
            var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(count);
            command.barrier(VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdBuildAccelerationStructuresKHR(command.buffer(), build, stack.pointers(range));
            command.barrier(VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_COPY_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT_KHR);
        } catch (RuntimeException | Error failure) {
            if (result != null) context.retire(result::free);
            throw failure;
        }
        if (activeTlas != null) context.retire(activeTlas::free);
        activeTlas = result;
        return result;
    }

    private long writeInstances(int count) {
        long bytes = (long) count * VkAccelerationStructureInstanceKHR.SIZEOF;
        var buffer = context.memory.createBuffer(bytes,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 16, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        context.retire(buffer::free);
        long address = buffer.map();
        try (var stack = stackPush()) {
            var instances = VkAccelerationStructureInstanceKHR.create(address, count);
            var matrix = new Matrix4x3f();
            var transform = stack.mallocFloat(12);
            for (var instance : terrain.values()) {
                writeInstance(instances.get(), instance, matrix, transform, 0, 0);
            }
            if (entity != null) {
                writeInstance(instances.get(), entity, matrix, transform, ENTITY_INSTANCE_FLAG,
                        VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
            }
            buffer.flush();
        } finally {
            buffer.unmap();
        }
        return buffer.deviceAddress();
    }

    private static void writeInstance(VkAccelerationStructureInstanceKHR out, Instance instance, Matrix4x3f matrix,
            java.nio.FloatBuffer transform, int customFlag, int instanceFlags) {
        out.instanceCustomIndex(instance.slot() | customFlag).mask(0xff)
                .instanceShaderBindingTableRecordOffset(0).flags(instanceFlags)
                .accelerationStructureReference(instance.mesh().structure().deviceAddress);
        out.transform().matrix(matrix.translation(instance.x(), instance.y(), instance.z()).getTransposed(transform));
    }

    void clear() {
        terrain.values().forEach(this::retire);
        terrain.clear();
        clearEntity();
        if (activeTlas != null) {
            context.retire(activeTlas::free);
            activeTlas = null;
        }
    }

    @Override
    public void close() {
        clear();
        context.retire(() -> {
            geometryPool.free();
            geometryLayout.free();
        });
    }

    long getGeometrySet() { return geometryPool.get(0); }
    VDescriptorSetLayout getGeometryLayout() { return geometryLayout; }
    VBuffer getEntityMotionBuffer() { return entityMotion; }
    int getTerrainSectionCount() { return terrain.size(); }
    Set<SectionPos> getTerrainSections() {
        return terrain.keySet().stream().map(RenderSection::getPosition).collect(Collectors.toUnmodifiableSet());
    }
}
