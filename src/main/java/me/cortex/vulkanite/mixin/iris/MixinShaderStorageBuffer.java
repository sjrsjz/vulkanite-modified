package me.cortex.vulkanite.mixin.iris;

import com.mojang.blaze3d.opengl.GlStateManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.compat.RaytracingPackState;
import me.cortex.vulkanite.lib.memory.VGBuffer;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

@Mixin(value = ShaderStorageBuffer.class, remap = false)
public abstract class MixinShaderStorageBuffer implements IVGBuffer {

    @Shadow protected int index;
    @Shadow protected BuiltShaderStorageInfo info;
    @Shadow protected ByteBuffer content;
    @Shadow protected int id;

    @Shadow public abstract int getIndex();

    @Unique private Optional<VGBuffer> vkBuffer = Optional.empty();
    @Unique private boolean vulkanite$shared;

    // === IVGBuffer ===

    public Optional<VGBuffer> getBuffer() {
        return vkBuffer;
    }

    // === Constructor: delete Iris's plain GL buffer ===

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onConstructed(CallbackInfo ci) {
        if (!RaytracingPackState.isActive()) {
            return;
        }
        // Iris created a plain GL buffer via createBuffers(). Delete it.
        // Real buffer storage is created on-demand in resizeIfRelative / createStatic.
        IrisRenderSystem.deleteBuffers(this.id);
        this.id = 0;
        vulkanite$shared = true;
    }

    @Inject(method = "resizeIfRelative", at = @At("HEAD"), cancellable = true)
    private void vulkanite$resizeIfRelative(int width, int height, CallbackInfo ci) {
        if (!vulkanite$shared) {
            return;
        }
        if (!info.relative()) return;

        // Free old VGBuffer if present
        if (vkBuffer.isPresent()) {
            VGBuffer old = vkBuffer.get();
            Vulkanite.INSTANCE.addSyncedCallback(old::free);
            vkBuffer = Optional.empty();
        }

        long newWidth = (long) (width * info.scaleX());
        long newHeight = (long) (height * info.scaleY());
        long finalSize = (newHeight * newWidth) * info.size();

        VGBuffer buf = Vulkanite.INSTANCE.getCtx().memory.createSharedBuffer(
                finalSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vkBuffer = Optional.of(buf);
        this.id = buf.glId;
        vulkanite$clear(finalSize);
        IrisRenderSystem.bindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, index, this.id);
        ci.cancel();
    }

    @Inject(method = "createStatic", at = @At("HEAD"), cancellable = true)
    private void vulkanite$createStatic(CallbackInfo ci) {
        if (!vulkanite$shared) {
            return;
        }
        // Free old VGBuffer if replacing
        if (vkBuffer.isPresent()) {
            VGBuffer old = vkBuffer.get();
            Vulkanite.INSTANCE.addSyncedCallback(old::free);
            vkBuffer = Optional.empty();
        }

        VGBuffer buf = Vulkanite.INSTANCE.getCtx().memory.createSharedBuffer(
                info.size(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vkBuffer = Optional.of(buf);
        this.id = buf.glId;

        // Upload initial content if present
        if (content != null) {
            GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, this.id);
            GlStateManager._glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, content);
        } else {
            vulkanite$clear(info.size());
        }

        IrisRenderSystem.bindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, index, this.id);
        ci.cancel();
    }

    @Unique
    private void vulkanite$clear(long size) {
        org.lwjgl.opengl.GL45C.glClearNamedBufferSubData(id, GL43C.GL_R8, 0, size,
                GL43C.GL_RED, GL43C.GL_BYTE, (ByteBuffer) null);
    }

    @Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
    private void vulkanite$destroy(CallbackInfo ci) {
        if (!vulkanite$shared) {
            return;
        }
        IrisRenderSystem.bindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, index, 0);
        if (vkBuffer.isPresent()) {
            VGBuffer captured = vkBuffer.get();
            vkBuffer = Optional.empty();
            Vulkanite.INSTANCE.addSyncedCallback(captured::free);
        }
        MemoryUtil.memFree(content);
        ci.cancel();
    }
}
