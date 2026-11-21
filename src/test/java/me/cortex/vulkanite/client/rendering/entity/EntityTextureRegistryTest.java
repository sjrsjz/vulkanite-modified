package me.cortex.vulkanite.client.rendering.entity;

import net.minecraft.resources.Identifier;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class EntityTextureRegistryTest {
    private final EntityTextureRegistry registry = EntityTextureRegistry.INSTANCE;

    @Before public void reset() { registry.clear(); registry.beginFrame(); }

    @Test public void liveFrameTexturesCannotBeEvicted() {
        for (int i = 0; i < EntityTextureRegistry.CAPACITY; i++) assertEquals(i, registry.indexOf(texture(i)));
        assertEquals(-1, registry.indexOf(texture(999)));
        assertEquals(texture(0), registry.get(0));
    }

    @Test public void departedTexturesDoNotExhaustTheRegistryAcrossFrames() {
        for (int i = 0; i < EntityTextureRegistry.CAPACITY; i++) registry.indexOf(texture(i));
        registry.beginFrame();
        registry.markUsed(0);
        int reused = registry.indexOf(texture(999));
        assertTrue(reused > 0);
        assertEquals(texture(0), registry.get(0));
        assertEquals(texture(999), registry.get(reused));
    }

    @Test public void worldResetReleasesTextureSlots() {
        registry.indexOf(texture(1)); registry.clear();
        assertEquals(0, registry.size());
        assertEquals(0, registry.indexOf(texture(2)));
    }

    private static Identifier texture(int i) { return Identifier.fromNamespaceAndPath("test", "skin/" + i); }
}
