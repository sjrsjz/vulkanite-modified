package me.cortex.vulkanite.mixin.sodium.chunk;

import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import me.cortex.vulkanite.compat.RaytracingPackState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkRenderRebuildTask {
    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/ChunkBuild");

    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", at = @At("TAIL"))
    private void performExtraBuild(ChunkBuildContext buildContext, CancellationToken cancellationToken, CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (!RaytracingPackState.isActive()) return;

        var buildResult = cir.getReturnValue();
        if (buildResult == null) return;

        try {
            ((IAccelerationBuildResult) buildResult).setVertexFormat(((VertexFormatAccessor) buildContext.buffers).getVertexType());
            SodiumResultAdapter.compute(buildResult);

            // Sodium may discard this result. Only publish it once Sodium accepts it
            // on the render thread (MixinRenderSectionManager).
        } catch (Exception | AssertionError e) {
            ((IAccelerationBuildResult) buildResult).releaseAccelerationGeometry();
            LOGGER.error("Failed to compute acceleration geometry", e);
        }
    }
}
