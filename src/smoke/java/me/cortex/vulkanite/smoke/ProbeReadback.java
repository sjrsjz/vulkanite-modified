package me.cortex.vulkanite.smoke;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

import java.nio.ByteOrder;

final class ProbeReadback implements AutoCloseable {
    private static final int BYTES = 16;
    private final GpuBuffer copy;
    private final GpuFence fence;

    ProbeReadback(int binding) {
        var iris = (IrisRenderingPipeline) Iris.getPipelineManager().getPipeline().orElseThrow();
        var device = RenderSystem.getDevice();
        copy = device.createBuffer(() -> "Vulkanite smoke readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, BYTES);
        try {
            var commands = device.createCommandEncoder();
            commands.copyToBuffer(iris.getStorageBuffers().getBuffer(binding).slice(), copy.slice());
            fence = commands.createFence();
        } catch (RuntimeException | Error failure) {
            copy.close();
            throw failure;
        }
    }

    boolean verifyDirtLighting() {
        if (!fence.awaitCompletion(0)) return false;
        try (var mapped = copy.map(true, false)) {
            int marker = mapped.data().order(ByteOrder.nativeOrder()).getInt(0);
            if (marker != 0x4c494748) throw new AssertionError("Dirt RT did not evaluate a visible terrain area light");
            return true;
        }
    }

    boolean verify(int oldExpected, int newExpected, int expectedLights) {
        if (!fence.awaitCompletion(0)) return false;
        try (var mapped = copy.map(true, false)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            int oldRay = data.getInt(0);
            int newRay = data.getInt(4);
            int signature = data.getInt(8);
            int lights = data.getInt(12);
            if (oldRay != oldExpected || newRay != newExpected || signature != 0x56554c4b || lights != expectedLights) {
                throw new AssertionError("Iris compute readback: old ray=" + oldRay + ", new ray=" + newRay
                        + ", signature=0x" + Integer.toHexString(signature) + ", expected rays="
                        + oldExpected + "/" + newExpected + ", lights=" + lights + "/" + expectedLights);
            }
            return true;
        }
    }

    @Override
    public void close() {
        fence.close();
        copy.close();
    }
}
