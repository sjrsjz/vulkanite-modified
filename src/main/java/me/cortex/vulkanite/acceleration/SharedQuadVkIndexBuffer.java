package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public class SharedQuadVkIndexBuffer {
    public static final int TYPE = VK_INDEX_TYPE_UINT32;
    private static VBuffer indexBuffer = null;
    private static int currentQuadCount = 0;
    private static final java.util.List<VBuffer> retiredBuffers = new java.util.ArrayList<>();

    public synchronized static long getAddress(VContext context, int quadCount) {
        if (currentQuadCount < quadCount) {
            makeNewIndexBuffer(context, quadCount);
        }

        return indexBuffer.deviceAddress();
    }

    private static void makeNewIndexBuffer(VContext context, int quadCount) {
        if (indexBuffer != null) {
            retiredBuffers.add(indexBuffer);
        }

        ByteBuffer buffer = genQuadIdxs(quadCount);
        //TODO: dont harcode VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR and VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        indexBuffer = context.memory.createBuffer(buffer.remaining(),
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0,
                org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

        try {
            long address = indexBuffer.map();
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), address, buffer.remaining());
            indexBuffer.flush();
            indexBuffer.unmap();
            // Host writes happen before submissions on either queue; no cross-queue
            // GPU upload dependency or publication race is needed for this table.
        } finally {
            MemoryUtil.memFree(buffer);
        }

        currentQuadCount = quadCount;
    }

    public static synchronized void cleanup() {
        if (indexBuffer != null) indexBuffer.free();
        retiredBuffers.forEach(VBuffer::free);
        retiredBuffers.clear();
        indexBuffer = null;
        currentQuadCount = 0;
    }

    public static ByteBuffer genQuadIdxs(int quadCount) {
        //short[] idxs = {0, 1, 2, 0, 2, 3};

        int vertexCount = quadCount * 4;
        int indexCount = vertexCount * 3 / 2;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Integer.BYTES);
        IntBuffer idxs = buffer.asIntBuffer();
        //short[] idxs = new short[indexCount];

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {

            idxs.put(j, i);
            idxs.put(j + 1, (i + 1));
            idxs.put(j + 2, (i + 2));
            idxs.put(j + 3, (i));
            idxs.put(j + 4, (i + 2));
            idxs.put(j + 5, (i + 3));

            j += 6;
        }

        return buffer;
    }
}
