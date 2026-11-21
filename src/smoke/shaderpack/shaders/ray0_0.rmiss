#version 460 core
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadInEXT uint result;

void main() {
    result = 1234u;
}
