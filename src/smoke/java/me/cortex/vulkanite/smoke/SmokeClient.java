package me.cortex.vulkanite.smoke;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.RaytracingPackState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.LoggerFactory;

/** Automated test world, confined to build/smoke-client. */
public final class SmokeClient implements ClientModInitializer {
    private final boolean probe = Boolean.getBoolean("vulkanite.smokeProbe");
    private final boolean lightBvh = Boolean.getBoolean("vulkanite.lightBvhSmoke");
    private int ticks;
    private int phase;
    private int lightStep;
    private java.util.concurrent.CompletableFuture<Void> reload;
    private ProbeReadback readback;

    @Override public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> client.execute(() -> {
            var settings = new LevelSettings("Vulkanite regression", GameType.CREATIVE,
                    LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel("smoke-" + System.currentTimeMillis(), settings,
                    new WorldOptions(12345, false, false), WorldPresets::createTestWorldDimensions, null);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        if (++ticks < 120) return;
        try {
            var manager = Vulkanite.INSTANCE.getAccelerationManager();
            int lights = manager.getTerrainLightCount();
            if (phase != 4 && !RaytracingPackState.isActive()) throw new AssertionError("Native RT pipeline is inactive");
            switch (phase) {
                case 0 -> {
                    if (manager.getTerrainSectionCount() == 0) throw new AssertionError("No live terrain BLAS");
                    if (lightBvh) {
                        if (lightStep == 0) {
                            setLights(client, 0, true);
                            lightStep = 1;
                            ticks = 0;
                            return;
                        }
                        requireLights(lights, lightStep != 2);
                        if (!checkRays(55, 1234, lights)) return;
                        if (lightStep == 1 || lightStep == 2) {
                            log("PASS: " + (lightStep == 1 ? "emitter publication" : "emitter removal after block edit"));
                            setLights(client, 0, lightStep == 2);
                            lightStep++;
                            ticks = 0;
                            return;
                        }
                    } else if (!checkRays(55, 1234, 0)) return;
                    log("Live world loaded, " + manager.getTerrainSectionCount() + " terrain sections");
                    command(client, "tp @p 4096 100 0");
                }
                case 1 -> {
                    if (manager.getTerrainSections().stream().anyMatch(pos -> Math.abs(pos.x()) < 32)) {
                        throw new AssertionError("Unloaded terrain reappeared near the old player position");
                    }
                    if (lightBvh) requireLights(lights, lightStep == 4);
                    if (!checkRays(1234, 55, lights)) return;
                    if (lightBvh && lightStep == 3) {
                        log("PASS: old terrain lights removed after teleport/unload");
                        setLights(client, 4096, true);
                        lightStep = 4;
                        ticks = 0;
                        return;
                    }
                    log("PASS: old terrain removed after teleport/unload");
                    reload = client.reloadResourcePacks();
                }
                case 2 -> {
                    if (!reload.isDone()) return;
                    reload.join();
                    if (lightBvh) requireLights(lights, true);
                    log("PASS: resource-pack reload");
                    if (!org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(client.getWindow().handle(), 800, 450)) {
                        throw new AssertionError("Could not resize the test window");
                    }
                }
                case 3 -> {
                    var target = client.gameRenderer.mainRenderTarget();
                    if (target.width != 800 || target.height != 450) {
                        throw new AssertionError("Main render target did not resize: " + target.width + "x" + target.height);
                    }
                    log("PASS: window/SSBO resize");
                    Iris.toggleShaders(client, false);
                }
                case 4 -> {
                    if (RaytracingPackState.isActive()) throw new AssertionError("Ray tracing remained active with shaders disabled");
                    if (manager.getTerrainSectionCount() != 0) throw new AssertionError("Terrain retained after disabling shaders");
                    if (lightBvh) requireLights(lights, false);
                    log("PASS: shader disable");
                    Iris.toggleShaders(client, true);
                }
                case 5 -> {
                    if (manager.getTerrainSectionCount() == 0) throw new AssertionError("No terrain after re-enabling shaders");
                    if (lightBvh) requireLights(lights, true);
                    if (!checkRays(1234, 55, lights)) return;
                    log("PASS: shader re-enable");
                    String pack = probe ? "native ray hit/miss through Iris compute" : "live Dirt RT";
                    java.nio.file.Files.writeString(java.nio.file.Path.of("smoke-result.txt"),
                            "PASS: " + pack + (lightBvh ? ", Light BVH" : "") + ", unload, reload, resize, disable/re-enable\n");
                    client.stop();
                }
            }
            phase++; ticks = 0;
        } catch (Exception | AssertionError e) {
            if (readback != null) readback.close();
            log("FAIL: " + e);
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("smoke-result.txt"), "FAIL: " + e); }
            catch (java.io.IOException ignored) {}
            client.stop();
            throw new IllegalStateException("Vulkanite smoke test failed", e);
        }
    }

    private static void requireLights(int count, boolean expected) {
        if ((count > 0) != expected) throw new AssertionError("Terrain light count " + count + ", expected " + (expected ? "positive" : "zero"));
    }

    private static void command(Minecraft client, String text) {
        var server = client.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), text));
    }

    private static void setLights(Minecraft client, int x, boolean present) {
        String block = present ? "glowstone" : "air";
        command(client, "fill " + (x + 1) + " 7 1 " + (x + 4) + " 7 4 " + block);
        command(client, "setblock " + (x - 4) + " 7 1 " + block);
        if (present) command(client, "tp @p " + (x + 9.5) + " 4 2.5 90 30");
    }

    private boolean checkRays(int oldRay, int newRay, int expectedLights) {
        if (!probe && (!lightBvh || expectedLights == 0)) return true;
        if (readback == null) {
            readback = new ProbeReadback(probe ? 1 : Integer.getInteger("vulkanite.dirtLightProbeBinding", 7));
            return false;
        }
        if (probe ? !readback.verify(oldRay, newRay, expectedLights) : !readback.verifyDirtLighting()) return false;
        readback.close();
        readback = null;
        if (probe) {
            log("PASS: Iris compute readback of ray results " + oldRay + "/" + newRay);
            if (lightBvh) log("PASS: Light BVH GPU forward/reverse PDF and " + expectedLights + " terrain emitters");
        } else {
            log("PASS: Dirt RT evaluated a visible terrain area light from " + expectedLights + " emitters");
        }
        return true;
    }

    private static void log(String message) {
        LoggerFactory.getLogger("Vulkanite/Smoke").info(message);
    }
}
