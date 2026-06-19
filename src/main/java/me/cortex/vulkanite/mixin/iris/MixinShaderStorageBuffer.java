package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.lib.memory.VGBuffer;
import net.coderbot.iris.gl.IrisRenderSystem;
import net.coderbot.iris.gl.buffer.ShaderStorageBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

@Mixin(value = ShaderStorageBuffer.class, remap = false)
public abstract class MixinShaderStorageBuffer implements IVGBuffer {
    @Shadow
    protected int id;

    @Shadow
    public abstract int getIndex();

    @Unique
    private VGBuffer vkBuffer;

    public VGBuffer getBuffer() {
        return vkBuffer;
    }

    public void setBuffer(VGBuffer buffer) {
        if (vkBuffer != null && buffer != null) {
            throw new IllegalStateException("Override buffer not null");
        }
        this.vkBuffer = buffer;
        if (buffer != null) {
            glDeleteBuffers(id);
            id = vkBuffer.glId;
        }
    }

    // =======================================================================
    // hook the adaptive resize method to dynamically create and
    // replace it with a Vulkan shared buffer
    // =======================================================================
    @Inject(method = "resizeIfRelative", at = @At("TAIL"))
    private void onResizeIfRelative(int width, int height, CallbackInfo ci) {
        // 1. Get the actual OpenGL buffer size allocated by Iris after resizing
        //    based on the current window resolution
        int[] sizeContainer = new int[1];
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, this.id);
        org.lwjgl.opengl.GL15C.glGetBufferParameteriv(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER,
                org.lwjgl.opengl.GL15C.GL_BUFFER_SIZE, sizeContainer);
        int allocatedSize = sizeContainer[0];

        if (allocatedSize > 0) {
            // 2. When the window is resized, safely destroy and free the old GPU
            //    memory if a VGBuffer already exists to prevent leaks
            if (this.vkBuffer != null) {
                VGBuffer oldBuffer = this.vkBuffer;
                Vulkanite.INSTANCE.addSyncedCallback(oldBuffer::free);
                this.vkBuffer = null;
            }

            // 3. Dynamically create a Vulkan VGBuffer shared buffer that exactly
            //    matches the required size
            VGBuffer newVkBuffer = Vulkanite.INSTANCE.getCtx().memory.createSharedBuffer(
                    allocatedSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            // 4. Destroy the OpenGL buffer just created by Iris and physically
            //    replace it with shared memory
            glDeleteBuffers(this.id);
            this.vkBuffer = newVkBuffer;
            this.id = newVkBuffer.glId;

            // 5. Bind it to the corresponding base SSBO binding point so the
            //    Vulkan and OpenGL paths remain bridged
            org.lwjgl.opengl.GL30C.glBindBufferBase(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER, this.getIndex(),
                    this.id);
        }
    }

    @Redirect(method = "destroy", at = @At(value = "INVOKE", target = "Lnet/coderbot/iris/gl/IrisRenderSystem;deleteBuffers(I)V"))
    private void redirectDelete(int id) {
        if (vkBuffer != null) {
            Vulkanite.INSTANCE.addSyncedCallback(vkBuffer::free);
        } else {
            IrisRenderSystem.deleteBuffers(id);
        }
    }
}