#ifndef VULKANITE_TERRAIN_LIGHT_BVH_GLSL
#define VULKANITE_TERRAIN_LIGHT_BVH_GLSL

// Enable GL_EXT_buffer_reference and GL_EXT_shader_explicit_arithmetic_types_int64
// in the enclosing ray tracing shader.
layout(std430, set = 0, binding = 7) readonly buffer VltLightBvh {
    uvec4 header;
    uvec4 records[];
} vltLightBvh;

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer VltLightSection {
    uvec4 header;
    vec4 origin;
    uvec4 records[];
};

layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer VltQuadLookup {
    uvec2 entries[];
};

struct VltLightNode {
    vec4 minPower;
    vec3 maxCorner;
    uvec4 link;
};

struct VltLightSample {
    vec3 position;
    float pdf;
    uint geometry;
    uint primitive;
};

uint vltTerrainLightCount() {
    return vltLightBvh.header.w;
}

VltLightNode vltTopNode(uint index) {
    uint offset = index * 3u;
    return VltLightNode(uintBitsToFloat(vltLightBvh.records[offset]),
        uintBitsToFloat(vltLightBvh.records[offset + 1u].xyz), vltLightBvh.records[offset + 2u]);
}

VltLightNode vltLocalNode(VltLightSection section, uint index) {
    uint offset = index * 3u;
    return VltLightNode(uintBitsToFloat(section.records[offset]),
        uintBitsToFloat(section.records[offset + 1u].xyz), section.records[offset + 2u]);
}

VltLightSection vltSectionAt(VltLightNode top) {
    return VltLightSection(uint64_t(top.link.x) | (uint64_t(top.link.y) << 32u));
}

float vltLightImportance(VltLightNode node, vec3 point) {
    vec3 d = max(max(node.minPower.xyz - point, point - node.maxCorner), vec3(0.0));
    return node.minPower.w / (1.0 + dot(d, d));
}

vec2 vltLightBranchWeights(VltLightNode left, VltLightNode right, vec3 point) {
    return vec2(vltLightImportance(left, point), vltLightImportance(right, point));
}

uint vltChooseBranch(uvec2 children, vec2 weights, inout float xi, inout float probability) {
    float split = weights.x / (weights.x + weights.y);
    bool left = xi < split || split >= 1.0;
    float p = left ? split : 1.0 - split;
    xi = clamp((left ? xi : xi - split) / p, 0.0, 0.99999994);
    probability *= p;
    return left ? children.x : children.y;
}

float vltBranchPdf(vec2 weights, bool left) {
    float split = weights.x / (weights.x + weights.y);
    return left ? split : 1.0 - split;
}

uint vltEmitterOffset(VltLightSection section, uint emitter) {
    return section.header.x / 4u - 2u + 3u * emitter;
}

uvec4 vltEmitterInfo(VltLightSection section, uint emitter) {
    return section.records[vltEmitterOffset(section, emitter) + 2u];
}

void vltEmitterVertices(VltLightSection section, uint emitter, out vec3 a, out vec3 b,
    out vec3 c, out vec3 d) {
    uint offset = vltEmitterOffset(section, emitter);
    uvec4 ab = section.records[offset];
    uvec4 cd = section.records[offset + 1u];
    a = vec3(unpackHalf2x16(ab.x), unpackHalf2x16(ab.y).x);
    b = vec3(unpackHalf2x16(ab.z), unpackHalf2x16(ab.w).x);
    c = vec3(unpackHalf2x16(cd.x), unpackHalf2x16(cd.y).x);
    d = vec3(unpackHalf2x16(cd.z), unpackHalf2x16(cd.w).x);
}

float vltSolidAnglePdf(float probability, float area, vec3 normal, vec3 delta) {
    float distanceSq = dot(delta, delta);
    if (area <= 0.0 || distanceSq <= 1e-10 || probability <= 0.0) return 0.0;
    float cosine = abs(dot(normalize(normal), delta * inversesqrt(distanceSq)));
    return cosine > 1e-8 ? probability * distanceSq / (area * cosine) : 0.0;
}

bool vltSampleTerrainLight(vec3 point, vec3 randoms, out VltLightSample result) {
    if (vltTerrainLightCount() == 0u) return false;
    float xi = clamp(randoms.x, 0.0, 0.99999994);
    float probability = 1.0;
    VltLightNode node = vltTopNode(0u);
    while (node.link.w == 0u) {
        VltLightNode left = vltTopNode(node.link.x);
        VltLightNode right = vltTopNode(node.link.y);
        uint child = vltChooseBranch(node.link.xy, vltLightBranchWeights(left, right, point), xi, probability);
        node = child == node.link.x ? left : right;
    }

    VltLightSection section = vltSectionAt(node);
    vec3 localPoint = point - section.origin.xyz;
    node = vltLocalNode(section, 0u);
    while (node.link.w == 0u) {
        VltLightNode left = vltLocalNode(section, node.link.x);
        VltLightNode right = vltLocalNode(section, node.link.y);
        uint child = vltChooseBranch(node.link.xy, vltLightBranchWeights(left, right, localPoint), xi, probability);
        node = child == node.link.x ? left : right;
    }

    uint emitter = node.link.x;
    uvec4 info = vltEmitterInfo(section, emitter);
    float weight = uintBitsToFloat(info.w);
    float pick = xi * node.minPower.w;
    for (uint i = 1u; i < node.link.y && pick >= weight; i++) {
        pick -= weight;
        emitter++;
        info = vltEmitterInfo(section, emitter);
        weight = uintBitsToFloat(info.w);
    }
    probability *= weight / node.minPower.w;

    vec3 a, b, c, d;
    vltEmitterVertices(section, emitter, a, b, c, d);
    vec3 normal0 = cross(b - a, c - a);
    vec3 normal1 = cross(c - a, d - a);
    float area0 = 0.5 * length(normal0);
    float area1 = 0.5 * length(normal1);
    float area = area0 + area1;
    float trianglePick = clamp(randoms.y, 0.0, 0.99999994) * area;
    bool second = area0 == 0.0 || (area1 > 0.0 && trianglePick >= area0);
    float triangleU = second ? (trianglePick - area0) / area1 : trianglePick / area0;
    float s = sqrt(clamp(triangleU, 0.0, 1.0));
    vec3 p = mix(a, mix(second ? c : b, second ? d : c, randoms.z), s);
    result.position = section.origin.xyz + p;
    result.pdf = vltSolidAnglePdf(probability, area, second ? normal1 : normal0, p - localPoint);
    result.geometry = info.x;
    result.primitive = info.y * 2u + (second ? 1u : 0u);
    return result.pdf > 0.0;
}

float vltTerrainLightPdf(vec3 point, vec3 hit, uint geometry, uint primitive) {
    if (geometry >= vltLightBvh.header.z) return 0.0;
    uint lookupStart = vltLightBvh.header.y / 4u - 1u;
    uvec3 range = vltLightBvh.records[lookupStart + geometry].xyz;
    if (range.z == 0u) return 0.0;

    uint topIndex = range.x;
    VltLightNode top = vltTopNode(topIndex);
    VltLightSection section = vltSectionAt(top);
    VltQuadLookup lookup = VltQuadLookup(uint64_t(section) + uint64_t(section.header.y) * 4ul);
    uint quad = primitive >> 1u;
    uint first = range.y;
    uint end = first + range.z;
    uint limit = end;
    while (first < end) {
        uint mid = first + (end - first) / 2u;
        if (lookup.entries[mid].x < quad) first = mid + 1u;
        else end = mid;
    }
    if (first == limit || lookup.entries[first].x != quad) return 0.0;
    uint emitter = lookup.entries[first].y;
    uvec4 info = vltEmitterInfo(section, emitter);

    float probability = 1.0;
    for (uint parent = top.link.z; parent != 0xffffffffu; parent = top.link.z) {
        top = vltTopNode(parent);
        vec2 weights = vltLightBranchWeights(vltTopNode(top.link.x), vltTopNode(top.link.y), point);
        probability *= vltBranchPdf(weights, topIndex == top.link.x);
        topIndex = parent;
    }

    uint index = info.z;
    VltLightNode local = vltLocalNode(section, index);
    vec3 localPoint = point - section.origin.xyz;
    probability *= uintBitsToFloat(info.w) / local.minPower.w;
    for (uint parent = local.link.z; parent != 0xffffffffu; parent = local.link.z) {
        local = vltLocalNode(section, parent);
        vec2 weights = vltLightBranchWeights(vltLocalNode(section, local.link.x), vltLocalNode(section, local.link.y), localPoint);
        probability *= vltBranchPdf(weights, index == local.link.x);
        index = parent;
    }

    vec3 a, b, c, d;
    vltEmitterVertices(section, emitter, a, b, c, d);
    vec3 normal0 = cross(b - a, c - a);
    vec3 normal1 = cross(c - a, d - a);
    float area = 0.5 * (length(normal0) + length(normal1));
    return vltSolidAnglePdf(probability, area, (primitive & 1u) == 0u ? normal0 : normal1, hit - point);
}

#endif
