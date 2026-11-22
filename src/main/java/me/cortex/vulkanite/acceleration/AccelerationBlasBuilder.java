package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/** Builds terrain and entity BLAS from meshes captured before raster upload. */
final class AccelerationBlasBuilder {
    private final VContext context;

    AccelerationBlasBuilder(VContext context) {
        this.context = context;
    }

    record Geometry(int quads, NativeBuffer positions, NativeBuffer attributes, int flags, boolean lightEligible) {
        void free() {
            positions.free();
            attributes.free();
        }
    }

    record Mesh(VAccelerationStructure structure, List<VBuffer> attributes) {
        void free() {
            structure.free();
            attributes.forEach(VBuffer::free);
        }
    }

    Mesh build(VCmdBuff command, List<Geometry> input, int buildFlags) {
        var attributes = new ArrayList<VBuffer>(input.size());
        VAccelerationStructure structure = null;
        try (var stack = stackPush()) {
            var geometries = VkAccelerationStructureGeometryKHR.calloc(input.size(), stack);
            var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(input.size(), stack);
            var primitiveCounts = stack.mallocInt(input.size());
            for (int i = 0; i < input.size(); i++) {
                var geometry = input.get(i);
                var positions = upload(command, geometry.positions(),
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
                context.retire(positions::free);

                var attributeBuffer = context.memory.createBuffer(geometry.attributes().getLength(),
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                attributes.add(attributeBuffer);
                command.upload(context, MemoryUtil.memAddress(geometry.attributes().getDirectBuffer()),
                        attributeBuffer, geometry.attributes().getLength());

                long indices = SharedQuadVkIndexBuffer.getAddress(context, geometry.quads());
                geometries.get(i).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(geometry.flags())
                        .geometry(data -> data.triangles(triangles -> triangles.sType$Default()
                                .vertexData(address -> address.deviceAddress(positions.deviceAddress()))
                                .vertexFormat(VK_FORMAT_R16G16B16A16_SFLOAT).vertexStride(8)
                                .maxVertex(geometry.quads() * 4 - 1)
                                .indexData(address -> address.deviceAddress(indices))
                                .indexType(SharedQuadVkIndexBuffer.TYPE)));
                primitiveCounts.put(i, geometry.quads() * 2);
                ranges.get(i).primitiveCount(geometry.quads() * 2);
            }

            var build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .flags(buildFlags).pGeometries(geometries).geometryCount(geometries.remaining());
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(context.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), primitiveCounts, sizes);
            structure = context.memory.createAcceleration(sizes.accelerationStructureSize(), 256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            var scratch = context.memory.createBuffer(Math.max(1, sizes.buildScratchSize()),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, context.properties.scratchAlignment, 0);
            context.retire(scratch::free);
            build.dstAccelerationStructure(structure.structure).scratchData(address -> address.deviceAddress(scratch.deviceAddress()));

            command.barrier(VK_PIPELINE_STAGE_2_COPY_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_READ_BIT_KHR);
            vkCmdBuildAccelerationStructuresKHR(command.buffer(), build, stack.pointers(ranges));
            return new Mesh(structure, attributes);
        } catch (RuntimeException | Error failure) {
            attributes.forEach(buffer -> context.retire(buffer::free));
            if (structure != null) context.retire(structure::free);
            throw failure;
        }
    }

    private VBuffer upload(VCmdBuff command, NativeBuffer data, int usage) {
        var buffer = context.memory.createBuffer(data.getLength(), VK_BUFFER_USAGE_TRANSFER_DST_BIT | usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        try {
            command.upload(context, MemoryUtil.memAddress(data.getDirectBuffer()), buffer, data.getLength());
            return buffer;
        } catch (RuntimeException | Error failure) {
            context.retire(buffer::free);
            throw failure;
        }
    }
}
