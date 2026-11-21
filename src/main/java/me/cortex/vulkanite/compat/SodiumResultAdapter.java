package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SodiumResultAdapter {
    private static final float COPLANAR_CUTOUT_OFFSET = 1.0f / 64.0f;

    public static void compute(ChunkBuildOutput buildResult) {
        var ebr = (IAccelerationBuildResult) buildResult;
        var format = ebr.getVertexFormat().getVertexFormat();
        var blockIdElement = format.getElement("mc_Entity");
        var midTextureElement = format.getElement("mc_midTexCoord");
        // A meshing task can straddle a shader toggle and still use Sodium's compact format.
        if (blockIdElement == null || midTextureElement == null) return;
        int stride = format.getVertexSize();
        int blockIdOffset = blockIdElement.offset();
        int midTextureOffset = midTextureElement.offset();
        Set<QuadPositionKey> solidQuadPositions = collectSolidQuadPositions(buildResult, stride);

        Map<TerrainRenderPass, GeometryData> blasMap = new HashMap<>();
        Map<TerrainRenderPass, NativeBuffer> geomBuffersMap = new HashMap<>();
        ebr.setAccelerationGeometryData(blasMap);
        ebr.setGeometryBuffersData(geomBuffersMap);

        for (var pass : buildResult.meshes.entrySet()) {
            var vertData = pass.getValue().getVertexData();

            if (vertData.getLength() % stride != 0)
                throw new IllegalStateException("Mismatch length and stride");
            int vertexCount = vertData.getLength() / stride;
            if (vertexCount % 4 != 0)
                throw new IllegalStateException("Non multiple 4 vertex count");
            int quadCount = vertexCount / 4;
            if (quadCount == 0) continue;

            // BLAS vertex data: half-float positions (8 bytes/vertex)
            NativeBuffer blasBuffer = new NativeBuffer(vertexCount * 8);
            blasMap.put(pass.getKey(), new GeometryData(quadCount, blasBuffer));
            long blasAddr = MemoryUtil.memAddress(blasBuffer.getDirectBuffer());

            // Geometry buffer: 40-byte Vertex struct × 4 vertices per Quad (160 bytes/quad)
            // This exactly matches the shaderpack's data.glsl Vertex/Quad layout (old Iris 1.6.9 XHFP format).
            NativeBuffer geomBuffer = new NativeBuffer(quadCount * 40 * 4);
            geomBuffersMap.put(pass.getKey(), geomBuffer);
            ByteBuffer geomBuf = geomBuffer.getDirectBuffer();

            long srcVert = MemoryUtil.memAddress(vertData.getDirectBuffer());

            int[] segments = pass.getValue().getVertexSegments();
            int vertexStart = 0;
            int quadIdx = 0;

            for (int s = 0; s < segments.length; s += 2) {
                int segVertexCount = segments[s];
                if (segVertexCount == 0) continue;

                int segQuadCount = segVertexCount / 4;
                for (int q = 0; q < segQuadCount; q++) {
                    int startVertex = vertexStart + q * 4;
                    long quadOffset = (long) quadIdx * 40 * 4;
                    boolean offsetCoplanarCutout = pass.getKey() == DefaultTerrainRenderPasses.CUTOUT
                            && solidQuadPositions.contains(quadPositionKey(srcVert, stride, startVertex));
                    encodeQuad(srcVert, stride, startVertex, blockIdOffset, midTextureOffset,
                            blasAddr, (long) quadIdx * 4 * 8,
                            geomBuf, quadOffset, offsetCoplanarCutout);
                    quadIdx++;
                }
                vertexStart += segVertexCount;
            }

        }

        if (!blasMap.isEmpty()) {
            ebr.setAccelerationGeometryData(blasMap);
            ebr.setGeometryBuffersData(geomBuffersMap);
        } else {
            ebr.setAccelerationGeometryData(null);
            ebr.setGeometryBuffersData(null);
        }
    }

    private static Set<QuadPositionKey> collectSolidQuadPositions(ChunkBuildOutput buildResult, int stride) {
        var solidMesh = buildResult.meshes.get(DefaultTerrainRenderPasses.SOLID);
        if (solidMesh == null) {
            return Set.of();
        }

        var vertexData = solidMesh.getVertexData();
        int vertexCount = vertexData.getLength() / stride;
        long srcVert = MemoryUtil.memAddress(vertexData.getDirectBuffer());
        Set<QuadPositionKey> positions = new HashSet<>(vertexCount / 4);
        for (int vertex = 0; vertex + 3 < vertexCount; vertex += 4) {
            positions.add(quadPositionKey(srcVert, stride, vertex));
        }
        return positions;
    }

    private static QuadPositionKey quadPositionKey(long srcVertBase, int stride, int startVertex) {
        long[] positions = new long[4];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = unpackPosition20(srcVertBase, startVertex + i, stride);
        }
        Arrays.sort(positions);
        return new QuadPositionKey(positions[0], positions[1], positions[2], positions[3]);
    }

    private record QuadPositionKey(long a, long b, long c, long d) {
    }

    // ---- 20-bit packed position decode (CompactChunkVertex / Iris XHFP 1.11.2) ----
    // Inverse of CompactChunkVertex.quantizePosition/normalizePosition:
    //   quantized = ((MODEL_ORIGIN + v) / MODEL_RANGE) * 2^20, MODEL_ORIGIN=8.0, MODEL_RANGE=32.0

    private static float decodePosition20(int q) {
        return (q * (1f / (1 << 20))) * 32.0f - 8.0f;
    }

    private static long unpackPosition20(long srcVertBase, int vertexIdx, int stride) {
        long base = srcVertBase + (long) vertexIdx * stride;
        int wordHi = MemoryUtil.memGetInt(base);
        int wordLo = MemoryUtil.memGetInt(base + 4);
        int qx = (((wordHi >>>  0) & 0x3FF) << 10) | ((wordLo >>>  0) & 0x3FF);
        int qy = (((wordHi >>> 10) & 0x3FF) << 10) | ((wordLo >>> 10) & 0x3FF);
        int qz = (((wordHi >>> 20) & 0x3FF) << 10) | ((wordLo >>> 20) & 0x3FF);
        // Pack x/y/z into a single int with 11 bits each (max 20-bit value = 1048575 < 2048*1024 ≈ 2M, so 21 bits needed...)
        // We can't pack 3×20-bit into 32-bit. Use a different approach: decode all here or pass as float.
        // Actually encodeQuad decodes inline — let's just return packed 20-bit triplet.
        // For simplicity, store x in [0:20), y in [20:40), z in [40:60) — need a long.
        return ((long) qx) | (((long) qy) << 20) | (((long) qz) << 40);
    }

    private static float decodeXFromPacked(long packed) { return decodePosition20((int)(packed & 0xFFFFF)); }
    private static float decodeYFromPacked(long packed) { return decodePosition20((int)((packed >> 20) & 0xFFFFF)); }
    private static float decodeZFromPacked(long packed) { return decodePosition20((int)((packed >> 40) & 0xFFFFF)); }

    private static float decodeMidU(int midTC) {
        return (midTC & 0xFFFF) / 32768.0f;
    }

    private static float decodeMidV(int midTC) {
        return ((midTC >> 16) & 0xFFFF) / 32768.0f;
    }

    // Iris 1.11.2 biased texture coordinate at offset 12 (RG16_UINT):
    //   CompactChunkVertex.encodeTexture: bias = ±1, quantized = round(u*32768)+bias,
    //   stored = (quantized & 0x7FFF) | (sign(bias) << 15)
    private static float decodeBiasedTexCoord(long srcVertBase, int vertexIdx, int stride, boolean isU) {
        long base = srcVertBase + (long) vertexIdx * stride;
        int packed = MemoryUtil.memGetInt(base + 12);
        int value = isU ? (packed & 0xFFFF) : ((packed >> 16) & 0xFFFF);
        int quantized = value & 0x7FFF;
        boolean bit15 = (value & 0x8000) != 0;
        // bit15=1 means bias=-1 (original >= center): quantized = round(x*32768) - 1 → x = (quantized + 1) / 32768
        // bit15=0 means bias=+1 (original < center):  quantized = round(x*32768) + 1 → x = (quantized - 1) / 32768
        float biasCorrection = bit15 ? 1.0f : -1.0f;
        return (quantized + biasCorrection) / 32768.0f;
    }

    // ---- Half-float encoding (for BLAS vertex positions) ----

    public static int fromFloat(float fval) {
        int fbits = Float.floatToIntBits(fval);
        int sign = fbits >>> 16 & 0x8000;
        int val = (fbits & 0x7fffffff) + 0x1000;

        if (val >= 0x47800000) {
            if ((fbits & 0x7fffffff) >= 0x47800000) {
                if (val < 0x7f800000)
                    return sign | 0x7c00;
                return sign | 0x7c00 | (fbits & 0x007fffff) >>> 13;
            }
            return sign | 0x7bff;
        }
        if (val >= 0x38800000)
            return sign | val - 0x38000000 >>> 13;
        if (val < 0x33000000)
            return sign;
        val = (fbits & 0x7fffffff) >>> 23;
        return sign | ((fbits & 0x7fffff | 0x800000) + (0x800000 >>> val - 102) >>> 126 - val);
    }

    // ---- Encode one quad (4 vertices) into both BLAS buffer and 40-byte geometry buffer ----

    private static void encodeQuad(long srcVertBase, int stride, int startVertex, int blockIdOffset, int midTextureOffset,
                                   long blasAddr, long blasQuadOffset,
                                   ByteBuffer geomBuf, long geomQuadOffset,
                                   boolean offsetCoplanarCutout) {

        // Position, color, texture for all 4 vertices, plus compute normal from geometry
        float[] px = new float[4], py = new float[4], pz = new float[4];
        float[] uu  = new float[4], vv  = new float[4];
        int[] colors = new int[4];
        int[] lights = new int[4];
        float midU = 0, midV = 0;

        // Read vertex[0]'s Iris extension fields (same for all 4 vertices per quad)
        int blockId = 0;
        int midTC = 0;

        for (int i = 0; i < 4; i++) {
            int vi = startVertex + i;
            long posPacked = unpackPosition20(srcVertBase, vi, stride);
            px[i] = decodeXFromPacked(posPacked);
            py[i] = decodeYFromPacked(posPacked);
            pz[i] = decodeZFromPacked(posPacked);

            uu[i] = decodeBiasedTexCoord(srcVertBase, vi, stride, true);
            vv[i] = decodeBiasedTexCoord(srcVertBase, vi, stride, false);

            long base = srcVertBase + (long) vi * stride;
            colors[i] = MemoryUtil.memGetInt(base + 8);
            lights[i] = MemoryUtil.memGetInt(base + 16);

            if (i == 0) {
                blockId = (MemoryUtil.memGetInt(base + blockIdOffset) >> 1) - 1;
                midTC = MemoryUtil.memGetInt(base + midTextureOffset);
                midU = decodeMidU(midTC);
                midV = decodeMidV(midTC);
            }
        }

        // Geometric face normal, matching Iris's NormalHelper.computeFaceNormalManual: use
        // BOTH quad diagonals (v2-v0, v3-v1), not two edges from a single vertex. Two-edges-
        // from-v0 degenerates on thin/needle quads (plant cross-model quads are exactly this
        // shape), producing a garbage or zero-length cross product on some quads while being
        // fine on others - matching the plant POM breakage and dark-blotch symptoms.
        float dx0 = px[2] - px[0], dy0 = py[2] - py[0], dz0 = pz[2] - pz[0];
        float dx1 = px[3] - px[1], dy1 = py[3] - py[1], dz1 = pz[3] - pz[1];
        float normX = dy0 * dz1 - dz0 * dy1;
        float normY = dz0 * dx1 - dx0 * dz1;
        float normZ = dx0 * dy1 - dy0 * dx1;
        float len = (float) Math.sqrt(normX * normX + normY * normY + normZ * normZ);
        if (len > 0.0001f) { normX /= len; normY /= len; normZ /= len; }
        else { normX = 0; normY = 1; normZ = 0; }

        // Tangent, matching Iris's NormalHelper.computeTangent: try triangle (0,1,2) first,
        // fall back to (2,3,0) if the first is degenerate (mirrors XHFPTerrainVertex.
        // computeTangentForQuad's exact fallback order).
        float[] tan = computeTangent(normX, normY, normZ,
                px[0], py[0], pz[0], uu[0], vv[0],
                px[1], py[1], pz[1], uu[1], vv[1],
                px[2], py[2], pz[2], uu[2], vv[2]);
        if (tan == null) {
            tan = computeTangent(normX, normY, normZ,
                    px[2], py[2], pz[2], uu[2], vv[2],
                    px[3], py[3], pz[3], uu[3], vv[3],
                    px[0], py[0], pz[0], uu[0], vv[0]);
        }
        float tanX = tan != null ? tan[0] : 1f;
        float tanY = tan != null ? tan[1] : 0f;
        float tanZ = tan != null ? tan[2] : 0f;
        float tanW = tan != null ? tan[3] : 1f;

        // Material byte (from lights[0] upper bits)
        int material = (lights[0] >> 16) & 0xFF;
        int section = (lights[0] >> 24) & 0xFF;

        for (int i = 0; i < 4; i++) {
            // ---- BLAS buffer: half-float position (8 bytes/vertex) ----
            long blasVertOff = blasQuadOffset + (long) i * 8;
            float blasX = px[i];
            float blasY = py[i];
            float blasZ = pz[i];
            if (offsetCoplanarCutout) {
                // Layered block models (notably grass sides) use a tinted cutout quad
                // exactly on top of a solid quad. Give the overlay a stable nearest hit;
                // transparent texels still fall through in the any-hit shader.
                blasX += normX * COPLANAR_CUTOUT_OFFSET;
                blasY += normY * COPLANAR_CUTOUT_OFFSET;
                blasZ += normZ * COPLANAR_CUTOUT_OFFSET;
            }
            MemoryUtil.memPutShort(blasAddr + blasVertOff, (short) fromFloat(blasX));
            MemoryUtil.memPutShort(blasAddr + blasVertOff + 2, (short) fromFloat(blasY));
            MemoryUtil.memPutShort(blasAddr + blasVertOff + 4, (short) fromFloat(blasZ));
            MemoryUtil.memPutShort(blasAddr + blasVertOff + 6, (short) 0); // padding W

            // ---- 40-byte geometry buffer ----
            long off = geomQuadOffset + (long) i * 40;

            // offset 0/2/4: position as uint16 (old XHFP encoding: (v+8)*2048)
            geomBuf.putShort((int) off, (short) ((px[i] + 8.0f) * 2048.0f));
            geomBuf.putShort((int) off + 2, (short) ((py[i] + 8.0f) * 2048.0f));
            geomBuf.putShort((int) off + 4, (short) ((pz[i] + 8.0f) * 2048.0f));
            // offset 6: material byte
            geomBuf.put((int) off + 6, (byte) material);
            // offset 7: section byte
            geomBuf.put((int) off + 7, (byte) section);
            // offset 8: color (ABGR, copy as-is)
            geomBuf.putInt((int) off + 8, colors[i]);
            // offset 12/14: block_texture (raw normalized UV × 65536 cast to uint16)
            geomBuf.putShort((int) off + 12, (short) (uu[i] * 65536.0f));
            geomBuf.putShort((int) off + 14, (short) (vv[i] * 65536.0f));
            // offset 16: light_texture (2×uint16 packed into uint32, same as Sodium's light field low 16 bits)
            geomBuf.putInt((int) off + 16, lights[i]);
            // offset 20/22: mid_tex_coord (centroid of quad UVs, same for all 4 vertices)
            geomBuf.putShort((int) off + 20, (short) (midU * 65536.0f));
            geomBuf.putShort((int) off + 22, (short) (midV * 65536.0f));

            if (i == 0) {
                // offset 24: tangent (snorm8×4, handedness in w)
                geomBuf.put((int) off + 24, snorm8(tanX));
                geomBuf.put((int) off + 25, snorm8(tanY));
                geomBuf.put((int) off + 26, snorm8(tanZ));
                geomBuf.put((int) off + 27, snorm8(tanW));
                // offset 28: normal (snorm8×3, 1 byte pad).
                // Use our own geometrically-computed face normal (normX/Y/Z, from the cross
                // product of triangle edges above), NOT Iris's packed normal field. Iris 1.11.2
                // encodes normals with NormalHelper.encodeNormal() - an octahedral mapping
                // (project the unit sphere onto an octahedron, fold the lower hemisphere over
                // the diagonals), not a simple linear 12-bit-per-axis encoding. Decoding it with
                // the wrong (linear) formula only happens to be close to correct for normals in
                // specific regions of the sphere, explaining why some cube faces looked fine
                // while others were flat or wildly wrong (POM's TBN matrix, built from this
                // normal, was reading a mis-decoded direction on the affected faces).
                geomBuf.put((int) off + 28, snorm8(normX));
                geomBuf.put((int) off + 29, snorm8(normY));
                geomBuf.put((int) off + 30, snorm8(normZ));
                geomBuf.put((int) off + 31, (byte) 0); // pad
                // offset 32: block_id (uint16)
                geomBuf.putShort((int) off + 32, (short) blockId);
                // offset 34: render_type (uint16) — from the packed blockId ((id+1)<<1)|(renderType&1)
                int renderType = MemoryUtil.memGetInt(srcVertBase + (long) startVertex * stride + 20) & 1;
                geomBuf.putShort((int) off + 34, (short) renderType);
                // offset 36: mid_block (int32, 3×snorm8 + emission byte) — use decoded midTexCoord + 0 emission
                // Actually mid_block is from offset 32 in the Iris extended format. Just copy it.
                long midBlockBase = srcVertBase + (long) startVertex * stride;
                int midBlockRaw = MemoryUtil.memGetInt(midBlockBase + 32);
                geomBuf.putInt((int) off + 36, midBlockRaw);
            } else {
                // vertices 1-3: copy tangent/normal/block_id/mid_block from vertex 0
                geomBuf.putInt((int) off + 24, geomBuf.getInt((int) geomQuadOffset + 24));
                geomBuf.putInt((int) off + 28, geomBuf.getInt((int) geomQuadOffset + 28));
                geomBuf.putInt((int) off + 32, geomBuf.getInt((int) geomQuadOffset + 32));
                geomBuf.putInt((int) off + 36, geomBuf.getInt((int) geomQuadOffset + 36));
            }
        }
    }

    private static byte snorm8(float v) {
        return (byte) Math.max(-127, Math.min(127, Math.round(v * 127.0f)));
    }

    // Matches Iris's NormalHelper.computeTangent(Vector4f, normal, tri...) exactly: computes
    // the tangent from one triangle's edges + UV gradients, then derives handedness (w) by
    // comparing the tangent-cross-normal (predicted bitangent) against the UV-space bitangent.
    // Returns null if the tangent is degenerate (zero vector) - e.g. the triangle's UV mapping
    // is degenerate - signaling the caller to retry with the quad's other diagonal triangle.
    private static float[] computeTangent(float normalX, float normalY, float normalZ,
                                           float x0, float y0, float z0, float u0, float v0,
                                           float x1, float y1, float z1, float u1, float v1,
                                           float x2, float y2, float z2, float u2, float v2) {
        float edge1x = x1 - x0, edge1y = y1 - y0, edge1z = z1 - z0;
        float edge2x = x2 - x0, edge2y = y2 - y0, edge2z = z2 - z0;

        float deltaU1 = u1 - u0, deltaV1 = v1 - v0;
        float deltaU2 = u2 - u0, deltaV2 = v2 - v0;

        float fdenom = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float f = (fdenom == 0.0f) ? 1.0f : (1.0f / fdenom);

        float tangentx = f * (deltaV2 * edge1x - deltaV1 * edge2x);
        float tangenty = f * (deltaV2 * edge1y - deltaV1 * edge2y);
        float tangentz = f * (deltaV2 * edge1z - deltaV1 * edge2z);
        float tcoeff = rsqrt(tangentx * tangentx + tangenty * tangenty + tangentz * tangentz);
        tangentx *= tcoeff; tangenty *= tcoeff; tangentz *= tcoeff;

        if (tangentx == 0.0f && tangenty == 0.0f && tangentz == 0.0f) {
            return null;
        }

        float bitangentx = f * (-deltaU2 * edge1x + deltaU1 * edge2x);
        float bitangenty = f * (-deltaU2 * edge1y + deltaU1 * edge2y);
        float bitangentz = f * (-deltaU2 * edge1z + deltaU1 * edge2z);
        float bitcoeff = rsqrt(bitangentx * bitangentx + bitangenty * bitangenty + bitangentz * bitangentz);
        bitangentx *= bitcoeff; bitangenty *= bitcoeff; bitangentz *= bitcoeff;

        // predicted bitangent = tangent × normal
        float pbitangentx = tangenty * normalZ - tangentz * normalY;
        float pbitangenty = tangentz * normalX - tangentx * normalZ;
        float pbitangentz = tangentx * normalY - tangenty * normalX;

        float dot = (bitangentx * pbitangentx) + (bitangenty * pbitangenty) + (bitangentz * pbitangentz);
        float tangentW = (dot < 0) ? -1.0f : 1.0f;

        return new float[]{tangentx, tangenty, tangentz, tangentW};
    }

    private static float rsqrt(float value) {
        if (value == 0.0f) return 1.0f;
        return (float) (1.0 / Math.sqrt(value));
    }
}
