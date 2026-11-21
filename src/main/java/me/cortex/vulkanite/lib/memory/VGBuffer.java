package me.cortex.vulkanite.lib.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;

public class VGBuffer extends VBuffer {
    private static final Logger LOG = LoggerFactory.getLogger("Vulkanite/VGBuffer");
    public final int glId;
    private final long vkMemory;
    VGBuffer(VmaAllocator.BufferAllocation allocation, int glId) {
        super(allocation);
        this.glId = glId;
        this.vkMemory = allocation.ai.deviceMemory();
    }

    @Override
    public void free() {
        if (isFreed()) return;
        int errBefore = glGetError();
        if (errBefore != GL_NO_ERROR) {
            LOG.warn("VGBuffer.free: stale GL error 0x{} BEFORE glDeleteBuffers(glId={})",
                    Integer.toHexString(errBefore), glId);
        }
        glDeleteBuffers(glId);
        int errAfter = glGetError();
        if (errAfter != GL_NO_ERROR) {
            LOG.error("VGBuffer.free: glDeleteBuffers(glId={}) produced GL error 0x{}",
                    glId, Integer.toHexString(errAfter));
        }
        MemoryManager.ExternalMemoryTracker.release(this.vkMemory);
        super.free();
    }
}
