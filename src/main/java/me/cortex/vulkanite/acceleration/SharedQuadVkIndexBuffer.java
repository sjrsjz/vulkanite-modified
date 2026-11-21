package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public class SharedQuadVkIndexBuffer {
    public static final int TYPE = VK_INDEX_TYPE_UINT32;
    private static VBuffer indexBuffer = null;
    private static int currentQuadCount = 0;

    public synchronized static long getAddress(VContext context, int quadCount) {
        if (currentQuadCount < quadCount) {
            makeNewIndexBuffer(context, Math.max(quadCount, Math.max(1024, currentQuadCount * 2)));
        }

        return indexBuffer.deviceAddress();
    }

    private static void makeNewIndexBuffer(VContext context, int quadCount) {
        ByteBuffer buffer = genQuadIdxs(quadCount);
        VBuffer replacement = null;
        try {
            replacement = context.memory.createBuffer(buffer.remaining(), VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

            long address = replacement.map();
            try {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), address, buffer.remaining());
                replacement.flush();
            } finally {
                replacement.unmap();
            }
        } catch (RuntimeException | Error failure) {
            if (replacement != null) replacement.free();
            throw failure;
        } finally {
            MemoryUtil.memFree(buffer);
        }

        if (indexBuffer != null) context.retire(indexBuffer::free);
        indexBuffer = replacement;
        currentQuadCount = quadCount;
    }

    public static synchronized void cleanup(VContext context) {
        if (indexBuffer != null) context.retire(indexBuffer::free);
        indexBuffer = null;
        currentQuadCount = 0;
    }

    public static ByteBuffer genQuadIdxs(int quadCount) {
        int vertexCount = quadCount * 4;
        int indexCount = vertexCount * 3 / 2;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Integer.BYTES);
        IntBuffer idxs = buffer.asIntBuffer();

        int j = 0;
        for (int i = 0; i < vertexCount; i += 4) {

            idxs.put(j, i);
            idxs.put(j + 1, i + 1);
            idxs.put(j + 2, i + 2);
            idxs.put(j + 3, i);
            idxs.put(j + 4, i + 2);
            idxs.put(j + 5, i + 3);

            j += 6;
        }

        return buffer;
    }
}
