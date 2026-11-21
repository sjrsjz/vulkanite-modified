package me.cortex.vulkanite.smoke;

import me.cortex.vulkanite.client.Vulkanite;
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
    private int ticks;
    private int phase;
    private java.util.concurrent.CompletableFuture<Void> reload;

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
            if (Vulkanite.INSTANCE.getCtx().sync.isDeviceLost()) throw new AssertionError("Vulkan failed during live rendering");
            switch (phase) {
                case 0 -> {
                    if (manager.getTerrainSectionCount() == 0) throw new AssertionError("No live terrain BLAS");
                    log("Live world loaded, " + manager.getTerrainSectionCount() + " terrain sections");
                    client.getSingleplayerServer().execute(() -> client.getSingleplayerServer().getCommands()
                            .performPrefixedCommand(client.getSingleplayerServer().createCommandSourceStack(), "tp @p 4096 100 0"));
                }
                case 1 -> {
                    if (manager.getTerrainSections().stream().anyMatch(pos -> Math.abs(pos.x()) < 32)) {
                        throw new AssertionError("Unloaded terrain reappeared near the old player position");
                    }
                    log("PASS: old terrain removed after teleport/unload");
                    reload = client.reloadResourcePacks();
                }
                case 2 -> {
                    if (!reload.isDone()) return;
                    reload.join();
                    log("PASS: resource-pack reload");
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(org.lwjgl.glfw.GLFW.glfwGetCurrentContext(), 800, 450);
                }
                case 3 -> {
                    log("PASS: window/SSBO resize");
                    Iris.toggleShaders(client, false);
                }
                case 4 -> {
                    if (manager.getTerrainSectionCount() != 0) throw new AssertionError("Terrain retained after disabling shaders");
                    log("PASS: shader disable");
                    Iris.toggleShaders(client, true);
                }
                case 5 -> {
                    if (manager.getTerrainSectionCount() == 0) throw new AssertionError("No terrain after re-enabling shaders");
                    log("PASS: shader re-enable");
                    java.nio.file.Files.writeString(java.nio.file.Path.of("smoke-result.txt"), "PASS: live Dirt RT, unload, reload, resize, disable/re-enable\n");
                    client.stop();
                }
            }
            phase++; ticks = 0;
        } catch (Exception | AssertionError e) {
            log("FAIL: " + e);
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("smoke-result.txt"), "FAIL: " + e); }
            catch (java.io.IOException ignored) {}
            client.stop();
            throw new IllegalStateException("Vulkanite smoke test failed", e);
        }
    }

    private static void log(String message) {
        LoggerFactory.getLogger("Vulkanite/Smoke").info(message);
    }
}
