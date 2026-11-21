# Vulkanite for Minecraft 26.3

Vulkanite is a hardware ray tracing extension for Iris. It requests ray tracing features when Minecraft creates its Vulkan device and records acceleration structure builds and ray passes in the game's command buffer. Ray passes use Iris's native textures and storage buffers. Iris continues to manage the raster and compute passes, the shaderpack and its resources.

Vulkanite publishes terrain geometry after Sodium accepts the corresponding chunk build. Section identity and build versions keep stale results from restoring unloaded terrain. An accepted empty mesh also removes the previous geometry. An empty scene builds a zero-instance TLAS.

## Runtime

- Minecraft **26.3** with Java **25** and Fabric Loader **0.20.1**.
- Iris **1.12.1-snapshot** from upstream commit [`8ebd1c2`](https://github.com/IrisShaders/Iris/tree/8ebd1c2ba245cd4a20577f2a6eef83e3fae87a1e) and Sodium **0.9.3-beta.5+mc26.3**.
- Minecraft's Vulkan backend. Use the game argument `--graphicsBackend vulkan`.
- A Vulkan device with `VK_KHR_acceleration_structure`, `VK_KHR_ray_tracing_pipeline` and the shader features listed in [RayTracingFeatures.java](src/main/java/me/cortex/vulkanite/compat/RayTracingFeatures.java). Vulkanite enables its shaderpack extension when the device enabled this feature set.

## Build

Use JDK 25. In the directory containing this repository, build the upstream Iris snapshot.

```sh
git clone --branch 26.3-new https://github.com/IrisShaders/Iris.git iris
git -C iris checkout 8ebd1c2ba245cd4a20577f2a6eef83e3fae87a1e
cd iris
bash ./gradlew :fabric:jar --configure-on-demand
```

From the Vulkanite repository, build the mod and run the unit tests.

```sh
bash ./gradlew build
```

On Windows, use `gradlew.bat` in each repository. The build looks for `../iris/build/libs/iris-fabric-1.12.1-snapshot+mc26.3-local.jar`. Set `-PirisJar=/path/to/iris.jar` to use an Iris jar at another location. The mod jar is `build/libs/vulkanite-0.0.8-vulkan.1.jar`.

## Live tests

The bundled probe traces two rays and checks the results through Iris's compute pass and a GPU readback. It expects the old position to become a miss after teleport and chunk unload, and the new position to become a hit.

```sh
bash ./gradlew runSmokeClient -PsmokeTest
bash ./gradlew runSmokeClient -PsmokeTest -Pshaderpack="/path/to/Dirt RT"
```

Both tests exercise resource reload, window resize and shader toggling. The test uses a hidden game window and an isolated world in `build/smoke-client`. It copies the supplied pack's `shaders/` directory and writes `build/smoke-client/smoke-result.txt`. To reuse an asset cache, pass `-PassetsDir="/path/to/assets" -PassetIndex=34 -x downloadAssets`. Set `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` to enable an installed Vulkan validation layer.

Live tests were run on Windows with an NVIDIA RTX 4060 Laptop GPU.

The pinned Iris snapshot can reuse a destroyed push-descriptor layout after shaders are disabled and re-enabled. This produces `VUID-VkWriteDescriptorSet-dstSet-00320` in Iris's raster pipeline. The error also reproduced in an Iris and Sodium client without Vulkanite. [Iris shader-library cache](https://github.com/IrisShaders/Iris/blob/8ebd1c2ba245cd4a20577f2a6eef83e3fae87a1e/common/src/main/java/net/irisshaders/iris/vulkan/VulkanShaderCache.java#L132)

## Shaderpack interface

The port retains the four descriptor sets and the geometry encoding used by Vulkanite `rayN` passes. Iris exposes `VULKANITE=1` when ray tracing is available. Dispatch values in `(0, 1]` scale the corresponding screen dimension. Values greater than one use a fixed dispatch size. Passes in the same group must be independent. Vulkanite inserts a shader memory barrier between groups.

## License and credits

Original Vulkanite by mcrcortex and Minecraft 26.2 port by sjrsjz. See [LICENSE.txt](LICENSE.txt).
