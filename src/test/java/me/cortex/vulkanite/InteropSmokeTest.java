package me.cortex.vulkanite;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.vulkanite.acceleration.*;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.*;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.*;
import me.cortex.vulkanite.lib.shader.*;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.*;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;

/** Opt-in native tests using a hidden GL window; never alters a game installation. */
public final class InteropSmokeTest {
    public static void main(String[] args) throws Exception {
        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(32, 32, "Vulkanite native regression", 0, 0);
        if (window == 0) throw new AssertionError("Hidden GL context failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        RenderSystem.initRenderThread();
        // NativeBuffer consults Sodium options even outside Fabric. Use in-memory
        // defaults; do not run Sodium's disk/config initialization in this test.
        var options = net.caffeinemc.mods.sodium.client.SodiumClientMod.class.getDeclaredField("OPTIONS");
        options.setAccessible(true);
        options.set(null, net.caffeinemc.mods.sodium.client.gui.SodiumOptions.defaults());
        System.out.println("GPU: " + glGetString(GL_RENDERER));
        var ctx = Vulkanite.INSTANCE.getCtx();
        try {
            lifecycle(ctx);
            if (args.length > 0) compilePack(Path.of(args[0]).resolve("shaders"));
            System.out.println("PASS: native regressions");
        } finally {
            Vulkanite.INSTANCE.getAccelerationManager().cleanup();
            ctx.cmd.waitIdle();
            ctx.sync.drainCallbacks();
            glFinish(); Vulkanite.INSTANCE.fenceTick();
            glFinish(); Vulkanite.INSTANCE.fenceTick();
            SharedQuadVkIndexBuffer.cleanup();
            ctx.cleanup();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void lifecycle(VContext ctx) throws Exception {
        var manager = Vulkanite.INSTANCE.getAccelerationManager();
        var output = ctx.memory.createSharedBuffer(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        var readback = ctx.memory.createBuffer(16, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        glClearNamedBufferData(output.glId, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        var bytes = MemoryUtil.memAlloc(16);
        int glReadback = glCreateBuffers();
        glNamedBufferStorage(glReadback, 16, GL_DYNAMIC_STORAGE_BIT);
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, """
                #version 430
                layout(local_size_x=1) in;
                layout(std430,binding=0) readonly buffer Source { uint inputValue; };
                layout(std430,binding=1) writeonly buffer Destination { uint outputValue; };
                void main() { outputValue=inputValue; }
                """);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader); glLinkProgram(program); glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        var layout = new DescriptorSetLayoutBuilder().binding(0, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_SHADER_STAGE_ALL)
                .binding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_ALL).build(ctx);
        var sets = new VDescriptorPool(ctx, 0, 1, layout.types);
        sets.allocateSets(layout);
        var rgen = VShader.compileLoad(ctx, "interop.rgen", """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(set=0,binding=0) uniform accelerationStructureEXT scene;
                layout(set=0,binding=1,std430) buffer Output { uint result; };
                layout(location=0) rayPayloadEXT uint value;
                void main() {
                    value=0;
                    traceRayEXT(scene,gl_RayFlagsOpaqueEXT,255,0,0,0,vec3(0.25,0.125,0),0.001,vec3(0,0,1),100.0,0);
                    result=value;
                }
                """, VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        var miss = VShader.compileLoad(ctx, "interop.rmiss", """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(location=0) rayPayloadInEXT uint value;
                void main() { value=1234; }
                """, VK_SHADER_STAGE_MISS_BIT_KHR);
        var hit = VShader.compileLoad(ctx, "interop.rchit", """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(location=0) rayPayloadInEXT uint value;
                void main() { value=55; }
                """, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        var pipeline = new RaytracePipelineBuilder().addLayout(layout).setRayGen(rgen.named())
                .addMiss(miss.named()).addHit(hit.named(), null, null).build(ctx, 1);
        var pool = ctx.cmd.createSingleUsePool();
        java.util.function.IntConsumer trace = expected -> {
            var in = ctx.sync.createSharedBinarySemaphore();
            var link = ctx.sync.createBinarySemaphore();
            var out = ctx.sync.createSharedBinarySemaphore();
            in.glSignal(new int[]{output.glId}, new int[0], new int[0]);
            var tlas = manager.buildTLAS(in, link);
            if (tlas == null) throw new AssertionError("Missing empty TLAS");
            new DescriptorUpdateBuilder(ctx, 2).set(sets.get(0)).acceleration(0, tlas).buffer(1, output).apply();
            var cmd = pool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            ownership(ctx, cmd, output.buffer(), true);
            pipeline.bind(cmd); pipeline.bindDSet(cmd, sets.get(0)); pipeline.trace(cmd, 1, 1, 1);
            cmd.encodeBufferBarrier(output, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_TRANSFER_BIT);
            try (var stack = stackPush()) {
                vkCmdCopyBuffer(cmd.buffer, output.buffer(), readback.buffer(), VkBufferCopy.calloc(1, stack).size(16));
            }
            ownership(ctx, cmd, output.buffer(), false);
            cmd.end();
            var fence = ctx.sync.createFence();
            ctx.cmd.submit(0, new VCmdBuff[]{cmd}, new VSemaphore[]{link},
                    new int[]{VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR}, new VSemaphore[]{out}, fence);
            out.glWait(new int[]{output.glId}, new int[0], new int[0]);
            ctx.sync.waitForFence(fence);
            glFinish();
            // Validate the GPU consumer used by Iris. NVIDIA's direct CPU readback
            // of external-memory buffers can return a cached earlier value.
            glUseProgram(program);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, output.glId);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, glReadback);
            glDispatchCompute(1, 1, 1);
            org.lwjgl.opengl.GL42C.glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            glGetNamedBufferSubData(glReadback, 0, bytes);
            glUseProgram(0);
            int actual = bytes.getInt(0);
            long readbackAddress = readback.map();
            int gpuActual = MemoryUtil.memGetInt(readbackAddress);
            readback.unmap();
            System.out.println("Trace expected=" + expected + " Vulkan=" + gpuActual + " GL=" + actual);
            int error = glGetError();
            fence.free(); out.free(); link.free(); in.free(); pool.releaseNow(cmd);
            Vulkanite.INSTANCE.renderTick(); Vulkanite.INSTANCE.fenceTick();
            if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
            if (gpuActual != expected) throw new AssertionError("Vulkan readback: " + gpuActual);
            if (error != GL_NO_ERROR) throw new AssertionError("GL interop error: " + error);
        };
        try {
            trace.accept(1234);
            var entity = new Mesh(new RenderSection(null, 0, 0, 0), true);
            var entityVertices = entity.positions.values().iterator().next().data();
            var entityGeometry = entity.vertices.values().iterator().next();
            var motion = new NativeBuffer(32);
            MemoryUtil.memSet(MemoryUtil.memAddress(motion.getDirectBuffer()), 0, 32);
            manager.setEntityGeometry(new me.cortex.vulkanite.client.rendering.entity.EntityGeometryFrame(
                    entityVertices, entityGeometry, motion, 1, 0, 0, 0));
            trace.accept(55);
            System.out.println("PASS: entity BLAS hit");
            trace.accept(1234);
            var old = new RenderSection(null, 0, 0, 0);
            manager.chunkBuilds(List.of(new Mesh(old, true))); awaitSection(manager); trace.accept(55);
            manager.sectionRemove(old); trace.accept(1234);
            var replacement = new RenderSection(null, 0, 0, 0);
            manager.chunkBuilds(List.of(new Mesh(replacement, true))); awaitSection(manager);
            manager.sectionRemove(old); trace.accept(55);
            manager.chunkBuilds(List.of(new Mesh(replacement, false))); trace.accept(1234);
            for (int i = 0; i < 32; i++) {
                var stale = new RenderSection(null, 0, 0, 0);
                manager.chunkBuilds(List.of(new Mesh(stale, true))); manager.sectionRemove(stale);
            }
            manager.clearWorld(); trace.accept(1234);
            System.out.println("PASS: terrain unload, empty rebuild, replacement section, empty-scene ray tracing");
        } finally {
            ctx.cmd.waitIdle(); ctx.sync.checkFences(); pool.free(); pipeline.free();
            rgen.free(); miss.free(); hit.free(); sets.free(); layout.free();
            output.free(); readback.free(); MemoryUtil.memFree(bytes);
            glDeleteProgram(program); glDeleteBuffers(glReadback);
        }
    }

    private static void awaitSection(AccelerationManager manager) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (manager.getTerrainSectionCount() != 1 && System.nanoTime() < deadline) {
            Vulkanite.INSTANCE.renderTick(); Thread.sleep(5);
        }
        if (manager.getTerrainSectionCount() != 1) throw new AssertionError("BLAS worker did not publish");
    }

    private static void ownership(VContext ctx, VCmdBuff cmd, long buffer, boolean acquire) {
        try (var stack = stackPush()) {
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().buffer(buffer).size(VK_WHOLE_SIZE)
                    .srcQueueFamilyIndex(acquire ? VK_QUEUE_FAMILY_EXTERNAL : ctx.queueFamily)
                    .dstQueueFamilyIndex(acquire ? ctx.queueFamily : VK_QUEUE_FAMILY_EXTERNAL)
                    .srcAccessMask(acquire ? 0 : VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(acquire ? VK_ACCESS_SHADER_WRITE_BIT : 0);
            vkCmdPipelineBarrier(cmd.buffer, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    acquire ? VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, null, barrier, null);
        }
    }

    private static final class Mesh extends ChunkBuildOutput implements IAccelerationBuildResult {
        private Map<TerrainRenderPass, GeometryData> positions;
        private Map<TerrainRenderPass, NativeBuffer> vertices;
        Mesh(RenderSection section, boolean nonempty) {
            super(section, 1, null, null, Map.of(), false);
            if (!nonempty) return;
            var data = new NativeBuffer(32);
            var bytes = data.getDirectBuffer();
            float[] xyz = {-1,-1,1, 1,-1,1, 1,1,1, -1,1,1};
            for (int v = 0; v < 4; v++) {
                for (int a = 0; a < 3; a++) bytes.putShort(v * 8 + a * 2, Float.floatToFloat16(xyz[v * 3 + a]));
                bytes.putShort(v * 8 + 6, (short) 0);
            }
            var attributes = new NativeBuffer(160);
            MemoryUtil.memSet(MemoryUtil.memAddress(attributes.getDirectBuffer()), 0, 160);
            positions = Map.of(DefaultTerrainRenderPasses.SOLID, new GeometryData(1, data));
            vertices = Map.of(DefaultTerrainRenderPasses.SOLID, attributes);
        }
        public void setAccelerationGeometryData(Map<TerrainRenderPass, GeometryData> map) { positions=map; }
        public Map<TerrainRenderPass, GeometryData> getAccelerationGeometryData() { return positions; }
        public void setGeometryBuffersData(Map<TerrainRenderPass, NativeBuffer> map) { vertices=map; }
        public Map<TerrainRenderPass, NativeBuffer> getGeometryBuffersData() { return vertices; }
        public ChunkVertexType getVertexFormat() { return null; }
        public void setVertexFormat(ChunkVertexType type) {}
    }

    private static void compilePack(Path root) throws Exception {
        int count=0;
        try (var files=Files.list(root)) {
            for (Path file : files.sorted().toList()) {
                String name=file.getFileName().toString();
                int stage=name.endsWith(".rgen") ? VK_SHADER_STAGE_RAYGEN_BIT_KHR : name.endsWith(".rmiss") ? VK_SHADER_STAGE_MISS_BIT_KHR
                        : name.endsWith(".rchit") ? VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR : name.endsWith(".rahit") ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0;
                if (stage==0) continue;
                String source = net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor.glslPreprocessSource(
                        expand(root,file,new HashSet<>()), List.of(
                                new net.irisshaders.iris.helpers.StringPair("MC_GL_NV_gpu_shader5", "1"),
                                new net.irisshaders.iris.helpers.StringPair("MC_VERSION", "260200"),
                                new net.irisshaders.iris.helpers.StringPair("VULKANITE", "1")));
                MemoryUtil.memFree(ShaderCompiler.compileShader(name, source, stage));
                count++; System.out.println("Compiled " + name);
            }
        }
        if (count==0) throw new AssertionError("No RT shaders in " + root);
        System.out.println("PASS: " + count + " Dirt RT shader stages");
    }

    private static String expand(Path root,Path source,Set<Path> stack) throws Exception {
        source=source.toAbsolutePath().normalize();
        if (!stack.add(source)) throw new IllegalArgumentException("Recursive include: " + source);
        var result=new StringBuilder();
        var include=Pattern.compile("\\s*#include\\s+\"([^\"]+)\".*");
        for (String line : Files.readAllLines(source)) {
            var match=include.matcher(line);
            if (match.matches()) {
                String name=match.group(1);
                result.append(expand(root,name.startsWith("/") ? root.resolve(name.substring(1)) : source.getParent().resolve(name),stack));
            } else result.append(line).append('\n');
        }
        stack.remove(source);
        return result.toString();
    }
}
