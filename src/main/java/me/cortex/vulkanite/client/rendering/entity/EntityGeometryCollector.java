package me.cortex.vulkanite.client.rendering.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import me.cortex.vulkanite.mixin.minecraft.RenderSetupAccessor;
import me.cortex.vulkanite.mixin.minecraft.RenderSetupTextureBindingAccessor;
import me.cortex.vulkanite.mixin.minecraft.RenderTypeAccessor;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityGeometryCollector {
    public static final EntityGeometryCollector INSTANCE = new EntityGeometryCollector();
    private static final int GEOMETRY_VERTEX_SIZE = 40;
    private static final int UNSUPPORTED_TEXTURE = -1;
    private static final int BLOCK_ATLAS_TEXTURE = -2;
    private static final long UNKNOWN_OWNER = -1L;
    private static final long BLOCK_ENTITY_OWNER_DOMAIN = 0x4000000000000000L;
    private static final long FIRST_PERSON_HAND_OWNER = 0x6000000000000000L;
    private static final float MAX_TRACKED_MOTION = 16.0f;
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/EntityGeometry");

    private final List<CapturedVertex> vertices = new ArrayList<>();
    private final Map<SubmissionKey, SubmissionSnapshot> previousSnapshots = new HashMap<>();
    private final Map<SubmissionKey, SubmissionSnapshot> currentSnapshots = new HashMap<>();
    private final Map<SubmissionBaseKey, Integer> submissionOccurrences = new HashMap<>();
    private final ArrayDeque<Long> entitySubmissionOwners = new ArrayDeque<>();
    private final List<RawSubmission> handSubmissions = new ArrayList<>();
    private final List<RawSubmission> pendingHandSubmissions = new ArrayList<>();
    private Vec3 cameraPosition = Vec3.ZERO;
    private boolean rayTracingActive;
    private boolean frameOpen;
    private boolean handFrameOpen;
    private boolean loggedFirstFrame;
    private long frameNumber;
    private int matchedSubmissions;
    private int missingHistorySubmissions;
    private int vertexCountMismatchSubmissions;
    private int topologyMismatchSubmissions;
    private int excessiveMotionSubmissions;
    private int unknownOwnerSubmissions;
    private String firstFailureSample;
    private long handFrameNumber;
    private int handCapturedSubmissions;
    private int handCapturedVertices;
    private int handRejectedRenderType;
    private int handRejectedTexture;
    private int handRejectedRegistry;
    private String firstHandSample;

    private EntityGeometryCollector() {
    }

    public void beginFrame(Vec3 cameraPosition, Matrix4fc viewRotationMatrix, Matrix4fc handViewTransform) {
        EntityTextureRegistry.INSTANCE.beginFrame();
        vertices.clear();
        currentSnapshots.clear();
        submissionOccurrences.clear();
        entitySubmissionOwners.clear();
        matchedSubmissions = 0;
        missingHistorySubmissions = 0;
        vertexCountMismatchSubmissions = 0;
        topologyMismatchSubmissions = 0;
        excessiveMotionSubmissions = 0;
        unknownOwnerSubmissions = 0;
        firstFailureSample = null;
        this.cameraPosition = cameraPosition;
        this.frameOpen = true;
        appendPendingHand(viewRotationMatrix, handViewTransform);
    }

    public void beginHandFrame() {
        if (!rayTracingActive) {
            return;
        }
        handSubmissions.clear();
        handCapturedSubmissions = 0;
        handCapturedVertices = 0;
        handRejectedRenderType = 0;
        handRejectedTexture = 0;
        handRejectedRegistry = 0;
        firstHandSample = null;
        handFrameOpen = true;
    }

    public void endHandFrame() {
        if (!handFrameOpen) {
            return;
        }
        handFrameOpen = false;
        pendingHandSubmissions.clear();
        pendingHandSubmissions.addAll(handSubmissions);
        logHandDiagnostics();
        handSubmissions.clear();
    }

    public void setRayTracingActive(boolean active) {
        rayTracingActive = active;
        if (!active) {
            resetHistory();
        }
    }

    public void resetHistory() {
        vertices.clear();
        previousSnapshots.clear();
        currentSnapshots.clear();
        submissionOccurrences.clear();
        entitySubmissionOwners.clear();
        handSubmissions.clear();
        pendingHandSubmissions.clear();
        frameOpen = false;
        handFrameOpen = false;
    }

    public boolean isFrameOpen() {
        return frameOpen;
    }

    public boolean isRayTracingActive() {
        return rayTracingActive;
    }

    public void beginEntitySubmission(int entityId) {
        entitySubmissionOwners.push(Integer.toUnsignedLong(entityId));
    }

    public void beginBlockEntitySubmission(BlockPos blockPos) {
        entitySubmissionOwners.push(BLOCK_ENTITY_OWNER_DOMAIN ^ blockPos.asLong());
    }

    public void endEntitySubmission() {
        if (!entitySubmissionOwners.isEmpty()) {
            entitySubmissionOwners.pop();
        }
    }

    public long currentSubmissionOwner() {
        return entitySubmissionOwners.isEmpty() ? UNKNOWN_OWNER : entitySubmissionOwners.peek();
    }

    public <S> boolean capture(ModelFeatureRenderer.Submit<S> submit) {
        if (!isCapturing()) {
            return false;
        }
        if (submit.renderType().isOutline() || submit.sheetedDecalPose() != null) {
            handRejectedRenderType += handFrameOpen ? 1 : 0;
            return false;
        }

        Identifier texture = textureOf(submit.renderType(), submit.sprite());
        if (texture == null) {
            handRejectedTexture += handFrameOpen ? 1 : 0;
            return false;
        }
        int textureIndex = EntityTextureRegistry.INSTANCE.indexOf(texture);
        if (textureIndex < 0) {
            handRejectedRegistry += handFrameOpen ? 1 : 0;
            return false;
        }

        List<CapturedVertex> captured = new ArrayList<>();
        CapturingConsumer capture = new CapturingConsumer(textureIndex, captured);
        VertexConsumer consumer = submit.sprite() != null ? submit.sprite().wrap(capture) : capture;
        PoseStack poseStack = new PoseStack();
        poseStack.last().set(submit.pose());
        submit.model().setupAnim(submit.state());
        submit.model().renderToBuffer(poseStack, consumer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
        capture.finish();
        long ownerId = handFrameOpen ? FIRST_PERSON_HAND_OWNER : submit.state() instanceof EntityRenderState state
                ? Integer.toUnsignedLong(((EntityRenderStateExtension) state).vulkanite$getEntityId())
                : ((EntityOwnedSubmissionExtension) (Object) submit).vulkanite$getOwnerKey();
        appendSubmission(captured, submissionBaseKey("model", ownerId, submit.model().getClass().getName(),
                texture, submit.pose()));
        return true;
    }

    public boolean capture(BlockModelFeatureRenderer.Submit submit) {
        if (!isCapturing() || submit.renderType().isOutline()
                || submit.sheetedDecalPose() != null) {
            return false;
        }
        if (!canCapture(submit.modelParts())) {
            return false;
        }

        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(submit.lightCoords());
        instance.setOverlayCoords(submit.overlayCoords());
        for (BlockStateModelPart part : submit.modelParts()) {
            for (Direction direction : Direction.values()) {
                captureBlockQuads(part.getQuads(direction), submit, instance);
            }
            captureBlockQuads(part.getQuads(null), submit, instance);
        }
        return true;
    }

    public boolean capture(ItemFeatureRenderer.Submit submit) {
        if (!isCapturing() || submit.outlineColor() != 0) {
            handRejectedRenderType += handFrameOpen ? 1 : 0;
            return false;
        }
        for (BakedQuad quad : submit.quads()) {
            if (textureIndex(quad) == UNSUPPORTED_TEXTURE) {
                handRejectedTexture += handFrameOpen ? 1 : 0;
                return false;
            }
        }

        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(submit.lightCoords());
        instance.setOverlayCoords(submit.overlayCoords());
        long ownerId = handFrameOpen ? FIRST_PERSON_HAND_OWNER
                : ((EntityOwnedSubmissionExtension) (Object) submit).vulkanite$getOwnerKey();
        for (BakedQuad quad : submit.quads()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            int color = material.isTinted() && tintIndex < submit.tintLayers().length
                    ? submit.tintLayers()[tintIndex]
                    : -1;
            captureBakedQuad(submit.pose(), quad, instance, color, ownerId, "item");
        }
        return true;
    }

    private boolean canCapture(List<BlockStateModelPart> parts) {
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                if (!canCaptureQuads(part.getQuads(direction))) {
                    return false;
                }
            }
            if (!canCaptureQuads(part.getQuads(null))) {
                return false;
            }
        }
        return true;
    }

    private boolean canCaptureQuads(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (textureIndex(quad) == UNSUPPORTED_TEXTURE) {
                return false;
            }
        }
        return true;
    }

    private void captureBlockQuads(List<BakedQuad> quads, BlockModelFeatureRenderer.Submit submit,
            QuadInstance instance) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            boolean useTintLayer = tintIndex != -1 && tintIndex < submit.tintLayers().length;
            int color = useTintLayer
                    ? ARGB.multiply(submit.tintColor(), submit.tintLayers()[tintIndex])
                    : submit.tintColor();
            captureBakedQuad(submit.pose(), quad, instance, color);
        }
    }

    private void captureBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, int color) {
        captureBakedQuad(pose, quad, instance, color,
                handFrameOpen ? FIRST_PERSON_HAND_OWNER : UNKNOWN_OWNER, "baked");
    }

    private void captureBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, int color,
            long ownerId, String kind) {
        instance.setColor(color);
        int textureIndex = textureIndex(quad);
        List<CapturedVertex> captured = new ArrayList<>(4);
        CapturingConsumer capture = new CapturingConsumer(textureIndex, captured);
        capture.putBakedQuad(pose, quad, instance);
        capture.finish();
        Identifier atlas = quad.materialInfo().sprite().atlasLocation();
        appendSubmission(captured, submissionBaseKey(kind, ownerId, quad.getClass().getName(), atlas, pose));
    }

    private SubmissionBaseKey submissionBaseKey(String kind, long ownerId, String modelClass, Identifier texture,
            PoseStack.Pose pose) {
        int poseHash = ownerId == UNKNOWN_OWNER ? poseHash(pose) : 0;
        return new SubmissionBaseKey(kind, ownerId, modelClass, texture, poseHash);
    }

    private void appendSubmission(List<CapturedVertex> captured, SubmissionBaseKey baseKey) {
        if (captured.isEmpty()) {
            return;
        }
        if (handFrameOpen && !frameOpen) {
            handSubmissions.add(new RawSubmission(captured, baseKey));
            handCapturedSubmissions++;
            handCapturedVertices += captured.size();
            if (firstHandSample == null) {
                firstHandSample = baseKey.toString();
            }
            return;
        }
        int occurrence = submissionOccurrences.merge(baseKey, 1, Integer::sum) - 1;
        SubmissionKey key = new SubmissionKey(baseKey, occurrence);
        int topologyHash = topologyHash(captured);
        SubmissionSnapshot previous = previousSnapshots.get(key);
        float motionStatus;
        if (baseKey.ownerId == UNKNOWN_OWNER) {
            unknownOwnerSubmissions++;
        }
        if (previous == null) {
            motionStatus = 0.0f;
            missingHistorySubmissions++;
        } else if (previous.positions.length != captured.size() * 3) {
            motionStatus = -1.0f;
            vertexCountMismatchSubmissions++;
        } else if (previous.topologyHash != topologyHash) {
            motionStatus = -2.0f;
            topologyMismatchSubmissions++;
        } else {
            motionStatus = 1.0f;
        }

        if (motionStatus > 0.5f) {
            double originDeltaX = cameraPosition.x - previous.originX;
            double originDeltaY = cameraPosition.y - previous.originY;
            double originDeltaZ = cameraPosition.z - previous.originZ;
            float maxAbs = 0.0f;
            for (int i = 0; i < captured.size(); i++) {
                CapturedVertex vertex = captured.get(i);
                int offset = i * 3;
                vertex.motionX = (float) (vertex.x - previous.positions[offset] + originDeltaX);
                vertex.motionY = (float) (vertex.y - previous.positions[offset + 1] + originDeltaY);
                vertex.motionZ = (float) (vertex.z - previous.positions[offset + 2] + originDeltaZ);
                maxAbs = Math.max(maxAbs, Math.max(Math.abs(vertex.motionX),
                        Math.max(Math.abs(vertex.motionY), Math.abs(vertex.motionZ))));
            }
            if (!Float.isFinite(maxAbs) || maxAbs > MAX_TRACKED_MOTION) {
                motionStatus = -3.0f;
                excessiveMotionSubmissions++;
            } else {
                matchedSubmissions++;
            }
        }

        if (motionStatus < 0.5f && firstFailureSample == null) {
            firstFailureSample = "status=" + motionStatus + ", key=" + key
                    + ", previousEntries=" + previousSnapshots.size();
        }

        float[] positions = new float[captured.size() * 3];
        for (int i = 0; i < captured.size(); i++) {
            CapturedVertex vertex = captured.get(i);
            int offset = i * 3;
            positions[offset] = vertex.x;
            positions[offset + 1] = vertex.y;
            positions[offset + 2] = vertex.z;
            vertex.motionStatus = motionStatus;
            if (motionStatus < 0.5f) {
                vertex.motionX = 0.0f;
                vertex.motionY = 0.0f;
                vertex.motionZ = 0.0f;
            }
        }
        currentSnapshots.put(key, new SubmissionSnapshot(positions, topologyHash,
                cameraPosition.x, cameraPosition.y, cameraPosition.z));
        vertices.addAll(captured);
    }

    private boolean isCapturing() {
        return rayTracingActive && (frameOpen || handFrameOpen);
    }

    private void appendPendingHand(Matrix4fc viewRotationMatrix, Matrix4fc handViewTransform) {
        if (pendingHandSubmissions.isEmpty()) {
            return;
        }
        Matrix4f handToWorld = new Matrix4f(viewRotationMatrix).invert().mul(handViewTransform);
        for (RawSubmission raw : pendingHandSubmissions) {
            List<CapturedVertex> transformed = new ArrayList<>(raw.vertices.size());
            for (CapturedVertex source : raw.vertices) {
                EntityTextureRegistry.INSTANCE.markUsed(source.textureIndex);
                CapturedVertex vertex = source.copy();
                Vector3f position = handToWorld.transformPosition(
                        new Vector3f(source.x, source.y, source.z));
                Vector3f normal = handToWorld.transformDirection(
                        new Vector3f(source.nx, source.ny, source.nz)).normalize();
                vertex.x = position.x;
                vertex.y = position.y;
                vertex.z = position.z;
                vertex.nx = normal.x;
                vertex.ny = normal.y;
                vertex.nz = normal.z;
                transformed.add(vertex);
            }
            appendSubmission(transformed, raw.key);
        }
    }

    private static int topologyHash(List<CapturedVertex> captured) {
        int hash = 1;
        for (CapturedVertex vertex : captured) {
            hash = 31 * hash + Float.floatToIntBits(vertex.u);
            hash = 31 * hash + Float.floatToIntBits(vertex.v);
            hash = 31 * hash + vertex.textureIndex;
        }
        return hash;
    }

    private static int poseHash(PoseStack.Pose pose) {
        var matrix = pose.pose();
        int hash = 1;
        hash = 31 * hash + Float.floatToIntBits(matrix.m00());
        hash = 31 * hash + Float.floatToIntBits(matrix.m01());
        hash = 31 * hash + Float.floatToIntBits(matrix.m02());
        hash = 31 * hash + Float.floatToIntBits(matrix.m03());
        hash = 31 * hash + Float.floatToIntBits(matrix.m10());
        hash = 31 * hash + Float.floatToIntBits(matrix.m11());
        hash = 31 * hash + Float.floatToIntBits(matrix.m12());
        hash = 31 * hash + Float.floatToIntBits(matrix.m13());
        hash = 31 * hash + Float.floatToIntBits(matrix.m20());
        hash = 31 * hash + Float.floatToIntBits(matrix.m21());
        hash = 31 * hash + Float.floatToIntBits(matrix.m22());
        hash = 31 * hash + Float.floatToIntBits(matrix.m23());
        hash = 31 * hash + Float.floatToIntBits(matrix.m30());
        hash = 31 * hash + Float.floatToIntBits(matrix.m31());
        hash = 31 * hash + Float.floatToIntBits(matrix.m32());
        return 31 * hash + Float.floatToIntBits(matrix.m33());
    }

    private int textureIndex(BakedQuad quad) {
        Identifier atlas = quad.materialInfo().sprite().atlasLocation();
        return TextureAtlas.LOCATION_BLOCKS.equals(atlas)
                ? BLOCK_ATLAS_TEXTURE
                : EntityTextureRegistry.INSTANCE.indexOf(atlas);
    }

    public EntityGeometryFrame endFrame() {
        frameOpen = false;
        logMotionDiagnostics();
        int completeVertexCount = vertices.size() - vertices.size() % 4;
        int quadCount = completeVertexCount / 4;
        if (quadCount == 0) {
            vertices.clear();
            advanceMotionHistory();
            return null;
        }

        if (!loggedFirstFrame) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < completeVertexCount; i++) {
                CapturedVertex vertex = vertices.get(i);
                minX = Math.min(minX, vertex.x + (float) cameraPosition.x);
                minY = Math.min(minY, vertex.y + (float) cameraPosition.y);
                minZ = Math.min(minZ, vertex.z + (float) cameraPosition.z);
                maxX = Math.max(maxX, vertex.x + (float) cameraPosition.x);
                maxY = Math.max(maxY, vertex.y + (float) cameraPosition.y);
                maxZ = Math.max(maxZ, vertex.z + (float) cameraPosition.z);
            }
            int nonDegenerateTriangles = 0;
            for (int quad = 0; quad < quadCount; quad++) {
                List<CapturedVertex> q = vertices.subList(quad * 4, quad * 4 + 4);
                nonDegenerateTriangles += triangleAreaSquared(q.get(0), q.get(1), q.get(2)) > 1.0e-12f ? 1 : 0;
                nonDegenerateTriangles += triangleAreaSquared(q.get(0), q.get(2), q.get(3)) > 1.0e-12f ? 1 : 0;
            }
            LOGGER.info("Captured first RT entity frame: {} quads, {}/{} non-degenerate triangles, {} textures, "
                            + "bounds=[({}, {}, {})..({}, {}, {})]",
                    quadCount, nonDegenerateTriangles, quadCount * 2, EntityTextureRegistry.INSTANCE.size(),
                    minX, minY, minZ, maxX, maxY, maxZ);
            loggedFirstFrame = true;
        }

        NativeBuffer blasVertices = new NativeBuffer(completeVertexCount * 4 * Short.BYTES);
        NativeBuffer geometry = new NativeBuffer(completeVertexCount * GEOMETRY_VERTEX_SIZE);
        NativeBuffer motion = new NativeBuffer(completeVertexCount * 4 * Short.BYTES);
        ByteBuffer blas = blasVertices.getDirectBuffer();
        ByteBuffer geom = geometry.getDirectBuffer();
        ByteBuffer motionData = motion.getDirectBuffer();

        for (int quad = 0; quad < quadCount; quad++) {
            encodeQuad(vertices.subList(quad * 4, quad * 4 + 4), blas, geom, motionData, quad);
        }
        vertices.clear();
        advanceMotionHistory();
        return new EntityGeometryFrame(blasVertices, geometry, motion, quadCount,
                (float) cameraPosition.x, (float) cameraPosition.y, (float) cameraPosition.z);
    }

    private void advanceMotionHistory() {
        previousSnapshots.clear();
        previousSnapshots.putAll(currentSnapshots);
        currentSnapshots.clear();
    }

    private void logMotionDiagnostics() {
        frameNumber++;
        if (frameNumber % 120L != 0L) {
            return;
        }
        LOGGER.info("Entity motion history frame {}: matched={}, missing={}, vertexCount={}, topology={}, "
                        + "excessive={}, unknownOwner={}, currentEntries={}, previousEntries={}, firstFailure=[{}]",
                frameNumber, matchedSubmissions, missingHistorySubmissions, vertexCountMismatchSubmissions,
                topologyMismatchSubmissions, excessiveMotionSubmissions, unknownOwnerSubmissions,
                currentSnapshots.size(), previousSnapshots.size(), firstFailureSample);
    }

    private void logHandDiagnostics() {
        handFrameNumber++;
        if (handFrameNumber > 3L && handFrameNumber % 120L != 0L) {
            return;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (RawSubmission submission : pendingHandSubmissions) {
            for (CapturedVertex vertex : submission.vertices) {
                minX = Math.min(minX, vertex.x);
                minY = Math.min(minY, vertex.y);
                minZ = Math.min(minZ, vertex.z);
                maxX = Math.max(maxX, vertex.x);
                maxY = Math.max(maxY, vertex.y);
                maxZ = Math.max(maxZ, vertex.z);
            }
        }
        String bounds = handCapturedVertices == 0 ? "empty"
                : "[(" + minX + ", " + minY + ", " + minZ + ")..(" + maxX + ", " + maxY + ", "
                        + maxZ + ")]";
        LOGGER.info("First-person RT capture frame {}: submissions={}, vertices={}, rejectedRenderType={}, "
                        + "rejectedTexture={}, rejectedRegistry={}, bounds={}, first=[{}]",
                handFrameNumber, handCapturedSubmissions, handCapturedVertices, handRejectedRenderType,
                handRejectedTexture, handRejectedRegistry, bounds, firstHandSample);
    }

    private static Identifier textureOf(RenderType renderType, TextureAtlasSprite sprite) {
        if (sprite != null) {
            return sprite.atlasLocation();
        }
        RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).vulkanite$getState();
        Map<String, Object> textures = ((RenderSetupAccessor) (Object) setup).vulkanite$getTextures();
        Object binding = textures.get("Sampler0");
        if (binding == null && !textures.isEmpty()) {
            binding = textures.values().iterator().next();
        }
        return binding != null
                ? ((RenderSetupTextureBindingAccessor) binding).vulkanite$getLocation()
                : null;
    }

    private static void encodeQuad(List<CapturedVertex> quad, ByteBuffer blas, ByteBuffer geom,
            ByteBuffer motion, int quadIndex) {
        CapturedVertex v0 = quad.get(0);
        CapturedVertex v1 = quad.get(1);
        CapturedVertex v2 = quad.get(2);
        float[] tangent = tangent(v0, v1, v2);
        float midU = 0.0f;
        float midV = 0.0f;
        for (CapturedVertex vertex : quad) {
            midU += vertex.u;
            midV += vertex.v;
        }
        midU *= 0.25f;
        midV *= 0.25f;

        for (int i = 0; i < 4; i++) {
            CapturedVertex vertex = quad.get(i);
            int blasOffset = (quadIndex * 4 + i) * 4 * Short.BYTES;
            blas.putShort(blasOffset, (short) SodiumResultAdapter.fromFloat(vertex.x));
            blas.putShort(blasOffset + 2, (short) SodiumResultAdapter.fromFloat(vertex.y));
            blas.putShort(blasOffset + 4, (short) SodiumResultAdapter.fromFloat(vertex.z));
            blas.putShort(blasOffset + 6, (short) 0);

            int offset = (quadIndex * 4 + i) * GEOMETRY_VERTEX_SIZE;
            boolean blockAtlas = vertex.textureIndex == BLOCK_ATLAS_TEXTURE;
            // Dirt RT decodes entity-texture positions as fp16, terrain-atlas positions as fixed point.
            geom.putShort(offset, me.cortex.vulkanite.compat.GeometryEncoding.entityPosition(vertex.x, blockAtlas));
            geom.putShort(offset + 2, me.cortex.vulkanite.compat.GeometryEncoding.entityPosition(vertex.y, blockAtlas));
            geom.putShort(offset + 4, me.cortex.vulkanite.compat.GeometryEncoding.entityPosition(vertex.z, blockAtlas));
            geom.put(offset + 6, (byte) 0);
            geom.put(offset + 7, (byte) 0);
            geom.put(offset + 8, (byte) vertex.r);
            geom.put(offset + 9, (byte) vertex.g);
            geom.put(offset + 10, (byte) vertex.b);
            geom.put(offset + 11, (byte) vertex.a);
            geom.putShort(offset + 12, (short) Math.round(vertex.u * 65535.0f));
            geom.putShort(offset + 14, (short) Math.round(vertex.v * 65535.0f));
            geom.putShort(offset + 16, (short) vertex.lightU);
            geom.putShort(offset + 18, (short) vertex.lightV);
            geom.putShort(offset + 20, (short) Math.round(midU * 65535.0f));
            geom.putShort(offset + 22, (short) Math.round(midV * 65535.0f));
            geom.put(offset + 24, snorm8(tangent[0]));
            geom.put(offset + 25, snorm8(tangent[1]));
            geom.put(offset + 26, snorm8(tangent[2]));
            geom.put(offset + 27, snorm8(tangent[3]));
            geom.put(offset + 28, snorm8(vertex.nx));
            geom.put(offset + 29, snorm8(vertex.ny));
            geom.put(offset + 30, snorm8(vertex.nz));
            geom.put(offset + 31, (byte) 0);
            geom.putShort(offset + 32, (short) (blockAtlas ? -1 : -2));
            geom.putShort(offset + 34, (short) (blockAtlas ? 0 : vertex.textureIndex + 1));
            geom.putInt(offset + 36, 0);

            int motionOffset = (quadIndex * 4 + i) * 4 * Short.BYTES;
            motion.putShort(motionOffset, (short) SodiumResultAdapter.fromFloat(vertex.motionX));
            motion.putShort(motionOffset + 2, (short) SodiumResultAdapter.fromFloat(vertex.motionY));
            motion.putShort(motionOffset + 4, (short) SodiumResultAdapter.fromFloat(vertex.motionZ));
            motion.putShort(motionOffset + 6,
                    (short) SodiumResultAdapter.fromFloat(vertex.motionStatus));
        }
    }

    private static short encodeGeometryPosition(float value) {
        return (short) Math.round((value + 8.0f) * 2048.0f);
    }

    private static byte snorm8(float value) {
        return (byte) Math.max(-127, Math.min(127, Math.round(value * 127.0f)));
    }

    private static float triangleAreaSquared(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
        float abx = b.x - a.x;
        float aby = b.y - a.y;
        float abz = b.z - a.z;
        float acx = c.x - a.x;
        float acy = c.y - a.y;
        float acz = c.z - a.z;
        float cx = aby * acz - abz * acy;
        float cy = abz * acx - abx * acz;
        float cz = abx * acy - aby * acx;
        return cx * cx + cy * cy + cz * cz;
    }

    private static float[] tangent(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
        float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
        float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
        float du1 = b.u - a.u, dv1 = b.v - a.v;
        float du2 = c.u - a.u, dv2 = c.v - a.v;
        float denominator = du1 * dv2 - du2 * dv1;
        if (Math.abs(denominator) < 1.0e-8f) {
            return new float[] { 1.0f, 0.0f, 0.0f, 1.0f };
        }
        float f = 1.0f / denominator;
        float tx = f * (dv2 * e1x - dv1 * e2x);
        float ty = f * (dv2 * e1y - dv1 * e2y);
        float tz = f * (dv2 * e1z - dv1 * e2z);
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > 1.0e-8f) {
            tx /= length;
            ty /= length;
            tz /= length;
        }
        float bx = f * (-du2 * e1x + du1 * e2x);
        float by = f * (-du2 * e1y + du1 * e2y);
        float bz = f * (-du2 * e1z + du1 * e2z);
        float px = ty * a.nz - tz * a.ny;
        float py = tz * a.nx - tx * a.nz;
        float pz = tx * a.ny - ty * a.nx;
        float handedness = bx * px + by * py + bz * pz < 0.0f ? -1.0f : 1.0f;
        return new float[] { tx, ty, tz, handedness };
    }

    private static final class CapturingConsumer implements VertexConsumer {
        private final int textureIndex;
        private final List<CapturedVertex> output;
        private CapturedVertex current;

        private CapturingConsumer(int textureIndex, List<CapturedVertex> output) {
            this.textureIndex = textureIndex;
            this.output = output;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            finish();
            current = new CapturedVertex();
            current.x = x;
            current.y = y;
            current.z = z;
            current.textureIndex = textureIndex;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            current.r = r;
            current.g = g;
            current.b = b;
            current.a = a;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            current.u = u;
            current.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            current.lightU = u;
            current.lightV = v;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            current.nx = x;
            current.ny = y;
            current.nz = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        private void finish() {
            if (current != null) {
                output.add(current);
                current = null;
            }
        }
    }

    private static final class CapturedVertex {
        float x, y, z;
        float u, v;
        float nx, ny, nz;
        int r = 255, g = 255, b = 255, a = 255;
        int lightU, lightV;
        int textureIndex;
        float motionX, motionY, motionZ;
        float motionStatus;

        CapturedVertex copy() {
            CapturedVertex copy = new CapturedVertex();
            copy.x = x;
            copy.y = y;
            copy.z = z;
            copy.u = u;
            copy.v = v;
            copy.nx = nx;
            copy.ny = ny;
            copy.nz = nz;
            copy.r = r;
            copy.g = g;
            copy.b = b;
            copy.a = a;
            copy.lightU = lightU;
            copy.lightV = lightV;
            copy.textureIndex = textureIndex;
            return copy;
        }
    }

    private record RawSubmission(List<CapturedVertex> vertices, SubmissionBaseKey key) {
    }

    private record SubmissionBaseKey(String kind, long ownerId, String modelClass, Identifier texture, int poseHash) {
    }

    private record SubmissionKey(SubmissionBaseKey base, int occurrence) {
    }

    private record SubmissionSnapshot(float[] positions, int topologyHash,
            double originX, double originY, double originZ) {
    }
}
