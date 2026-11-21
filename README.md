# Vulkanite for Minecraft 26.2

Vulkanite adds hardware ray tracing passes to Iris shaderpacks through OpenGL/Vulkan shared memory. This fork ports [mcrcortex's Vulkanite](https://github.com/mcrcortex/vulkanite) to Minecraft 26.2, maintained by [sjrsjz](https://github.com/sjrsjz).

## Requirements

- Minecraft 26.2, Java 25 or newer, Fabric Loader 0.19.3 or newer
- Sodium `mc26.2-0.9.1-fabric` and Iris `1.11.2+26.2-fabric` (the versions used for development and testing)
- A shaderpack with Vulkanite ray tracing passes, such as Dirt RT
- A GPU/driver exposing Vulkan ray tracing, acceleration structures, descriptor indexing, buffer device addresses, and external memory/semaphores
- OpenGL external memory/semaphore sharing on the **same physical GPU** as Vulkan

The Windows and Linux sharing paths are supported in code. Native regression tests for this revision were run on Windows with an NVIDIA RTX 4060 Laptop GPU; Linux and other GPU vendors have not been runtime tested.

## Changes in 0.0.7

### Terrain lifetime

Unloading a Sodium render section removes its ray tracing geometry. Work still in the background cannot add that section back. Meshes are published only after Sodium accepts the build result, and accepted empty meshes also remove the previous geometry. Section identity and monotonically increasing versions distinguish stale work from a new section at the same coordinates.

Clearing the world or destroying the shader pipeline invalidates pending terrain builds and clears entity history. An empty scene builds a valid zero-instance TLAS and completes the GL/Vulkan semaphore chain, so the last nonempty scene cannot remain visible.

### Rendering and interop

- Lazy Vulkan initialization: ordinary Iris shaderpacks do not create a Vulkan context.
- Match the OpenGL and Vulkan devices by UUID and select a compute-capable queue family, with support for devices exposing only one queue.
- Initialize geometry descriptors before the first frame and respect acceleration-structure scratch and shader-binding-table alignment.
- Transfer shared image/buffer ownership between OpenGL and Vulkan, preserve compatible image layouts, and wait before reusing per-frame descriptors and uniforms.
- Initialize static and resized SSBO contents, tolerate unused binding slots, and preserve shared buffers when the window changes size.
- Support Iris custom texture wrappers, preserve sampler filtering/wrapping, and refresh dynamic texture copies before the GL signal.
- Convert existing Minecraft textures to shared storage when needed after enabling an RT pack.
- Encode entity-texture positions as FP16 as expected by Dirt RT; preserve the block-atlas geometry encoding.
- Reuse entity texture slots across frames without evicting textures referenced in the current frame.
- Read RT properties after Iris preprocessing, expose `VULKANITE`, and fill missing pass groups without silently merging unrelated passes.
- Compile large expanded shaders from heap memory rather than overflowing LWJGL's thread stack.

### Cleanup and failure handling

Worker shutdown, rejected meshes, command uploads, shared indices, pipelines, descriptor resources, VMA, device properties, and the Vulkan context now have explicit cleanup paths. Imported Linux file descriptors are owned by OpenGL after a successful import.

Fatal Vulkan submission or fence failures stop new RT work for the session before issuing a GL wait on an unsignaled semaphore. **Automatic Vulkan device recreation is not implemented.** Restart the client after a device loss. Resource leak stack traces are optional with `-Dvulkanite.traceResources=true`.

## Build and verification

Use JDK 25:

```sh
./gradlew build
```

The release jar is `build/libs/vulkanite-0.0.7.jar`. The standard build runs GPU-independent regression tests for section lifetime, shader settings, geometry encoding, and entity texture slot reuse.

On a machine with compatible native GL/Vulkan drivers, run the opt-in GPU test:

```sh
./gradlew interopTest -Pshaderpack="/path/to/Dirt RT"
```

This traces actual rays through loaded, unloaded, empty, and replacement terrain sections, tests entity and empty-scene TLAS behavior, and verifies the result through both Vulkan readback and an OpenGL GPU consumer. The optional shaderpack argument also compiles all of its ray tracing stages. Set `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` to enable an installed Vulkan validation layer.

The isolated live-client test creates a temporary world under `build/smoke-client`, copies only the supplied pack's `shaders/` directory, and tests teleport/unload, resource reload, window resize, and shader disable/re-enable:

```sh
./gradlew runSmokeClient -PsmokeTest -Pshaderpack="/path/to/Dirt RT"
```

It uses a hidden test window, writes `build/smoke-client/smoke-result.txt`, and never installs anything into an existing game instance. When reusing an existing asset cache, supply `-PassetsDir="/path/to/assets" -PassetIndex=32 -x downloadAssets`. Test sources are excluded from the release jar.

These checks cover geometry lifetime and pipeline execution. They do not establish complete visual correctness for every shader effect, entity render type, or third-party mod.

## Shaderpack interface

The port retains Iris XHFP terrain decoding and the existing Vulkanite geometry layout. `rayN` passes support two or three dispatch dimensions: values in `(0, 1]` scale with the screen size, while values greater than one specify fixed dispatch sizes. Passes in the same execution group run without an intervening barrier; changing groups inserts a shader memory barrier. Shaderpack authors must group only independent passes together.

## License and credits

Original Vulkanite by mcrcortex; Minecraft 26.2 port by sjrsjz. See [LICENSE.txt](LICENSE.txt) for the license.
