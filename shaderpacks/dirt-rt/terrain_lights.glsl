#ifndef DIRT_RT_VULKANITE_TERRAIN_LIGHTS_GLSL
#define DIRT_RT_VULKANITE_TERRAIN_LIGHTS_GLSL

#include "/lib/rt/vulkanite/light_bvh.glsl"

bool vltDirtLightHemisphere(vec3 wi, vec3 geometricNormal, vec3 shadingNormal) {
    return dot(wi, geometricNormal) > 0.0 && dot(wi, shadingNormal) > 0.0;
}

bool vltDirtSampleLight(vec3 point, vec3 geometricNormal, vec3 shadingNormal,
    out vec3 wi, out vec3 Li, out float pdf) {
    VltLightSample chosen;
    vec3 randoms = vec3(getRandom(), getRandom(), getRandom());
    if (!vltSampleTerrainLight(point, randoms, chosen)) return false;

    vec3 toLight = chosen.position - point;
    float lightDistance = length(toLight);
    if (lightDistance <= 0.002 || lightDistance >= 2048.0) return false;
    wi = toLight / lightDistance;
    if (!vltDirtLightHemisphere(wi, geometricNormal, shadingNormal)) return false;

    vec3 rayOrigin = point + geometricNormal * 0.001;
    vec3 endpoint = chosen.position - rayOrigin;
    float distance = length(endpoint);
    vec3 shadowDirection = endpoint / distance;
    vec3 hit, ignored;
    float tolerance = max(0.002, distance * 2e-6);
    float t = raycastMin(rayOrigin, shadowDirection, hit, ignored, true, true,
        false, 0.0001, distance + tolerance);
    if (t < 0.0 || abs(t - distance) > tolerance) return false;

    uint geometry, entityTexture, primitive;
    payload_unpackQuadIDs(tmp_Payload.data, geometry, entityTexture, primitive);
    if (geometry != chosen.geometry || (primitive >> 1u) != (chosen.primitive >> 1u)) return false;

    int blockId;
    vec3 visibility = payload_unpackShadow(tmp_Payload.data, blockId);
    if (isTransmissiveBlock(blockId)) return false;
    float previousCone = rtCurrentConeWidth;
    rtCurrentConeWidth = fma(t, rtCurrentConeSpread, previousCone);
    Material surface = evaluateMaterial(tmp_Payload, rayOrigin, shadowDirection, 1u);
    rtCurrentConeWidth = previousCone;
    Li = materialFromEvaluated(surface, blockId).light * visibility;
    pdf = chosen.pdf;
    return any(greaterThan(Li, vec3(0.0))) && !any(isnan(Li)) && !any(isinf(Li));
}

float vltDirtLightPdf(vec3 point, vec3 hit, vec3 wi, vec3 geometricNormal,
    vec3 shadingNormal, Payload hitPayload, int blockId) {
    if (isTransmissiveBlock(blockId) || !vltDirtLightHemisphere(wi, geometricNormal, shadingNormal)) return 0.0;
    uint geometry, entityTexture, primitive;
    payload_unpackQuadIDs(hitPayload.data, geometry, entityTexture, primitive);
    return vltTerrainLightPdf(point, hit, geometry, primitive);
}

#endif
