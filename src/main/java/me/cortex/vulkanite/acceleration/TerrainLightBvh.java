package me.cortex.vulkanite.acceleration;

import com.mojang.renderpearl.api.textures.GpuTexture;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.mixin.iris.PBRTextureManagerAccessor;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.irisshaders.iris.pbr.texture.PBRAtlasTexture;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/** Publishes the light sampling data for the same terrain versions present in the TLAS. */
public final class TerrainLightBvh implements AutoCloseable {
    public static final int BINDING = 7;
    private static final int TOP_HEADER_WORDS = 4;
    private static final int SECTION_HEADER_WORDS = 8;
    private static final int NODE_WORDS = 12;
    private static final int EMITTER_WORDS = 12;
    private static final int LEAF_CAPACITY = 4;

    private record Emitter(int geometry, int quad, int ordinal, int[] positions) {}
    private record Section(VBuffer buffer, LightTree.Bounds bounds, float power, int firstSlot,
                           int[] counts, int emitterCount) {}

    private final VContext context;
    private final Map<RenderSection, Section> sections = new IdentityHashMap<>();
    private VBuffer topBuffer;
    private TerrainEmission emission;
    private GpuTexture atlasTexture;
    private GpuTexture specularTexture;
    private boolean dirty = true;
    private int emitterCount;

    public TerrainLightBvh(VContext context) {
        this.context = context;
    }

    public boolean prepareAtlas(AbstractTexture atlas, AbstractTexture specular) {
        var texture = atlas.getTexture();
        var specularId = specular.getTexture();
        if (texture != atlasTexture || specularId != specularTexture) {
            clear();
            atlasTexture = texture;
            specularTexture = specularId;
            emission = null;
        }
        if (emission != null) return true;
        var loaded = ((PBRTextureManagerAccessor) PBRTextureManager.INSTANCE).vulkanite$getLoadedTextures();
        if (!loaded.containsKey(texture)) return false;
        emission = new TerrainEmission(atlas instanceof TextureAtlas blockAtlas && specular instanceof PBRAtlasTexture
                ? blockAtlas : null);
        return true;
    }

    void publish(VCmdBuff command, RenderSection renderSection, int firstSlot,
                 List<AccelerationBlasBuilder.Geometry> meshes) {
        var next = buildSection(command, renderSection, firstSlot, meshes);
        if (next == null) remove(renderSection);
        else {
            retire(sections.put(renderSection, next));
            emitterCount += next.emitterCount();
            dirty = true;
        }
    }

    void remove(RenderSection section) {
        retire(sections.remove(section));
    }

    private void retire(Section section) {
        if (section == null) return;
        context.retire(section.buffer()::free);
        emitterCount -= section.emitterCount();
        dirty = true;
    }

    void clear() {
        sections.values().forEach(this::retire);
        sections.clear();
    }

    public int emitterCount() {
        return emitterCount;
    }

    private Section buildSection(VCmdBuff command, RenderSection section, int firstSlot,
                                 List<AccelerationBlasBuilder.Geometry> meshes) {
        if (emission == null || emission.isEmpty()) return null;
        var emitters = new ArrayList<LightTree.Entry<Emitter>>();
        int[] counts = new int[meshes.size()];
        for (int geometry = 0; geometry < meshes.size(); geometry++) {
            var mesh = meshes.get(geometry);
            if (!mesh.lightEligible()) continue;
            int first = emitters.size();
            var positions = mesh.positions().getDirectBuffer();
            var attributes = mesh.attributes().getDirectBuffer();
            for (int quad = 0; quad < mesh.quads(); quad++) {
                float weight = emissionWeight(attributes, quad);
                if (weight > 0) {
                    var entry = emitter(positions, geometry + firstSlot, quad, emitters.size(), weight);
                    if (entry != null) emitters.add(entry);
                }
            }
            counts[geometry] = emitters.size() - first;
        }
        if (emitters.isEmpty()) return null;

        var tree = new LightTree<>(emitters, LEAF_CAPACITY);
        var root = tree.nodes.getFirst();
        int emitterOffset = SECTION_HEADER_WORDS + NODE_WORDS * tree.nodes.size();
        int lookupOffset = emitterOffset + EMITTER_WORDS * emitters.size();
        int words = lookupOffset + 2 * emitters.size();
        var data = MemoryUtil.memAlloc(Math.multiplyExact(words, Integer.BYTES));
        try {
            data.putInt(emitterOffset).putInt(lookupOffset).putInt(tree.nodes.size()).putInt(emitters.size());
            data.putFloat(section.getOriginX()).putFloat(section.getOriginY()).putFloat(section.getOriginZ()).putInt(0);
            for (var node : tree.nodes) writeNode(data, node, node.left(), node.right());

            int[] parents = new int[emitters.size()];
            for (int i = 0; i < tree.nodes.size(); i++) {
                var node = tree.nodes.get(i);
                if (node.leaf()) {
                    for (int j = 0; j < node.right(); j++) parents[node.left() + j] = i;
                }
            }
            int[] searchOrder = new int[emitters.size()];
            for (int i = 0; i < tree.entries.length; i++) {
                var entry = tree.entries[i];
                var emitter = entry.value();
                for (int xyzw : emitter.positions()) data.putInt(xyzw);
                data.putInt(emitter.geometry()).putInt(emitter.quad()).putInt(parents[i]).putFloat(entry.power());
                searchOrder[emitter.ordinal()] = i;
            }
            // Input entries were collected in geometry and quad order.
            for (var entry : emitters) data.putInt(entry.value().quad()).putInt(searchOrder[entry.value().ordinal()]);

            var buffer = upload(command, data.flip(), VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            var bounds = root.bounds().translated(section.getOriginX(), section.getOriginY(), section.getOriginZ());
            return new Section(buffer, bounds, root.power(), firstSlot, counts, emitters.size());
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private float emissionWeight(ByteBuffer attributes, int quad) {
        // Same UNORM UV fields as the terrain hit shader's 40-byte Vertex.
        int u = 0;
        int v = 0;
        int offset = quad * 160;
        for (int vertex = 0; vertex < 4; vertex++) {
            u += Short.toUnsignedInt(attributes.getShort(offset + 12));
            v += Short.toUnsignedInt(attributes.getShort(offset + 14));
            offset += 40;
        }
        return emission.weight(u / 4, v / 4);
    }

    private static LightTree.Entry<Emitter> emitter(ByteBuffer positions, int geometry, int quad, int ordinal, float weight) {
        int[] packed = new int[8];
        float[][] xyz = new float[4][3];
        int offset = quad * 32;
        for (int i = 0; i < 4; i++) {
            int xy = positions.getInt(offset + i * 8);
            int zw = positions.getInt(offset + i * 8 + 4);
            packed[i * 2] = xy;
            packed[i * 2 + 1] = zw;
            xyz[i][0] = Float.float16ToFloat((short) xy);
            xyz[i][1] = Float.float16ToFloat((short) (xy >>> 16));
            xyz[i][2] = Float.float16ToFloat((short) zw);
        }
        float area = triangleArea(xyz[0], xyz[1], xyz[2]) + triangleArea(xyz[0], xyz[2], xyz[3]);
        if (!(area > 0) || !Float.isFinite(area)) return null;
        float[] a = xyz[0], b = xyz[1], c = xyz[2], d = xyz[3];
        var bounds = new LightTree.Bounds(
                Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])),
                Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])),
                Math.min(Math.min(a[2], b[2]), Math.min(c[2], d[2])),
                Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])),
                Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1])),
                Math.max(Math.max(a[2], b[2]), Math.max(c[2], d[2])));
        return new LightTree.Entry<>(bounds, area * weight, new Emitter(geometry, quad, ordinal, packed));
    }

    private static float triangleArea(float[] a, float[] b, float[] c) {
        float ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2];
        float bx = c[0] - a[0], by = c[1] - a[1], bz = c[2] - a[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        return (float) (0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz));
    }

    public VBuffer record(VCmdBuff command) {
        if (!dirty) return topBuffer;
        var entries = new ArrayList<LightTree.Entry<Section>>(sections.size());
        int slots = 0;
        for (var section : sections.values()) {
            entries.add(new LightTree.Entry<>(section.bounds(), section.power(), section));
            slots = Math.max(slots, section.firstSlot() + section.counts().length);
        }
        var tree = new LightTree<>(entries, 1);
        int lookupOffset = TOP_HEADER_WORDS + NODE_WORDS * tree.nodes.size();
        int words = lookupOffset + 4 * slots;
        var data = MemoryUtil.memCalloc(Math.multiplyExact(words, Integer.BYTES));
        try {
            data.putInt(tree.nodes.size()).putInt(lookupOffset).putInt(slots).putInt(emitterCount);
            for (int i = 0; i < tree.nodes.size(); i++) {
                var node = tree.nodes.get(i);
                if (node.leaf()) {
                    var section = tree.entries[node.left()].value();
                    long address = section.buffer().deviceAddress();
                    writeNode(data, node, (int) address, (int) (address >>> 32));
                    int first = 0;
                    for (int geometry = 0; geometry < section.counts().length; geometry++) {
                        int count = section.counts()[geometry];
                        int offset = (lookupOffset + (section.firstSlot() + geometry) * 4) * Integer.BYTES;
                        data.putInt(offset, i).putInt(offset + 4, first).putInt(offset + 8, count);
                        first += count;
                    }
                } else {
                    writeNode(data, node, node.left(), node.right());
                }
            }
            var next = upload(command, data.rewind(), 0);
            command.barrier(VK_PIPELINE_STAGE_2_COPY_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_READ_BIT_KHR);
            if (topBuffer != null) context.retire(topBuffer::free);
            topBuffer = next;
            dirty = false;
            return next;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void writeNode(ByteBuffer data, LightTree.Node node, int left, int right) {
        var b = node.bounds();
        data.putFloat(b.minX()).putFloat(b.minY()).putFloat(b.minZ()).putFloat(node.power());
        data.putFloat(b.maxX()).putFloat(b.maxY()).putFloat(b.maxZ()).putInt(0);
        data.putInt(left).putInt(right).putInt(node.parent()).putInt(node.leaf() ? 1 : 0);
    }

    private VBuffer upload(VCmdBuff command, ByteBuffer data, int usage) {
        var buffer = context.memory.createBuffer(data.remaining(),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 16, 0);
        try {
            command.upload(context, MemoryUtil.memAddress(data), buffer, data.remaining());
            return buffer;
        } catch (RuntimeException | Error failure) {
            context.retire(buffer::free);
            throw failure;
        }
    }

    @Override
    public void close() {
        clear();
        if (topBuffer != null) context.retire(topBuffer::free);
        topBuffer = null;
    }
}
