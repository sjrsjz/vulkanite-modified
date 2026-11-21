package me.cortex.vulkanite.client.rendering.entity;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityTextureRegistry {
    public static final int CAPACITY = 256;
    public static final EntityTextureRegistry INSTANCE = new EntityTextureRegistry();

    private final Map<Identifier, Integer> indices = new HashMap<>();
    private final List<Identifier> textures = new ArrayList<>();
    private final long[] lastSeen = new long[CAPACITY];
    private long frame;

    private EntityTextureRegistry() {
    }

    public synchronized int indexOf(Identifier texture) {
        Integer existing = indices.get(texture);
        if (existing != null) {
            lastSeen[existing] = frame;
            return existing;
        }
        if (textures.size() >= CAPACITY) {
            int oldest = -1;
            for (int i = 0; i < CAPACITY; i++) {
                if (lastSeen[i] != frame && (oldest < 0 || lastSeen[i] < lastSeen[oldest])) oldest = i;
            }
            if (oldest >= 0) {
                indices.remove(textures.set(oldest, texture));
                indices.put(texture, oldest);
                lastSeen[oldest] = frame;
                return oldest;
            }
            return -1;
        }
        int index = textures.size();
        textures.add(texture);
        indices.put(texture, index);
        lastSeen[index] = frame;
        return index;
    }

    public synchronized void beginFrame() {
        frame++;
    }

    public synchronized void markUsed(int index) {
        if (index >= 0 && index < textures.size()) lastSeen[index] = frame;
    }

    public synchronized Identifier get(int index) {
        return index >= 0 && index < textures.size() ? textures.get(index) : null;
    }

    public synchronized int size() {
        return textures.size();
    }

    public synchronized void clear() {
        indices.clear();
        textures.clear();
        java.util.Arrays.fill(lastSeen, 0);
    }
}
