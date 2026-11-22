#version 430 core

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);

layout(binding = 0, std430) readonly buffer TraceResults {
    uint hits[2];
    uint lightCount;
    uint lightCheck;
};
layout(binding = 1, std430) writeonly buffer VerificationResults {
    uvec4 verified;
};

void main() {
    #ifdef VULKANITE_LIGHT_BVH_PROBE
    verified = uvec4(hits[0], hits[1], lightCheck != 0u ? 0x56554c4bu : 0xbad0bad0u, lightCount);
    #else
    verified = uvec4(hits[0], hits[1], 0x56554c4bu, 0u);
    #endif
}
