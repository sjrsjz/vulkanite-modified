package me.cortex.vulkanite.acceleration;

import org.junit.Test;
import static org.junit.Assert.*;

public class SectionBuildStateTest {
    @Test public void unloadingRejectsAnAlreadyRunningBuild() {
        var state = new SectionBuildState<Object>();
        Object section = new Object();
        long build = state.accept(section);
        state.remove(section);
        assertFalse(state.isCurrent(section, build));
    }

    @Test public void emptyMeshInvalidatesPreviousNonemptyMesh() {
        var state = new SectionBuildState<Object>();
        Object section = new Object();
        long geometry = state.accept(section);
        long empty = state.accept(section);
        assertFalse(state.isCurrent(section, geometry));
        assertTrue(state.isCurrent(section, empty));
    }

    @Test public void olderBuildCannotReplaceANewerAcceptedBuild() {
        var state = new SectionBuildState<Object>();
        Object section = new Object();
        long old = state.accept(section);
        long latest = state.accept(section);
        assertTrue(state.isCurrent(section, latest));
        assertFalse(state.isCurrent(section, old));
    }

    @Test public void equalCoordinatesDoNotIdentifyTheSameSection() {
        record Section(int x, int y, int z) {}
        var state = new SectionBuildState<Section>();
        var oldWorld = new Section(1, 2, 3);
        var newWorld = new Section(1, 2, 3);
        long old = state.accept(oldWorld);
        state.clear();
        long current = state.accept(newWorld);
        state.remove(oldWorld);
        assertFalse(state.isCurrent(oldWorld, old));
        assertTrue(state.isCurrent(newWorld, current));
    }

    @Test public void worldResetNeverReusesAnOldVersion() {
        var state = new SectionBuildState<Object>();
        Object section = new Object();
        long old = state.accept(section);
        state.clear();
        long latest = state.accept(section);
        assertNotEquals(old, latest);
        assertFalse(state.isCurrent(section, old));
    }

    @Test public void unloadIsVisibleToTheWorkerThread() throws Exception {
        var state = new SectionBuildState<Object>();
        Object section = new Object();
        long old = state.accept(section);
        var released = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> { released.await(); return state.isCurrent(section, old); });
            state.remove(section);
            released.countDown();
            assertFalse(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
