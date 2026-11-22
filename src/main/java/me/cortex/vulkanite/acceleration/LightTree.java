package me.cortex.vulkanite.acceleration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** A compact binary hierarchy whose leaves reference a range of input entries. */
final class LightTree<T> {
    record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        Bounds union(Bounds other) {
            return new Bounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }

        Bounds translated(float x, float y, float z) {
            return new Bounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
        }
    }

    record Entry<T>(Bounds bounds, float power, T value) {}
    record Node(Bounds bounds, float power, int left, int right, int parent, boolean leaf) {}

    final Entry<T>[] entries;
    final List<Node> nodes = new ArrayList<>();
    private final int leafCapacity;

    @SuppressWarnings("unchecked")
    LightTree(List<Entry<T>> entries, int leafCapacity) {
        this.entries = entries.toArray(Entry[]::new);
        this.leafCapacity = leafCapacity;
        if (this.entries.length > 0) build(0, this.entries.length, -1);
    }

    private int build(int first, int end, int parent) {
        Bounds bounds = entries[first].bounds();
        float power = entries[first].power();
        for (int i = first + 1; i < end; i++) {
            bounds = bounds.union(entries[i].bounds());
            power += entries[i].power();
        }

        int index = nodes.size();
        if (end - first <= leafCapacity) {
            nodes.add(new Node(bounds, power, first, end - first, parent, true));
            return index;
        }

        float x = bounds.maxX() - bounds.minX();
        float y = bounds.maxY() - bounds.minY();
        float z = bounds.maxZ() - bounds.minZ();
        Comparator<Entry<T>> byCenter;
        if (x >= y && x >= z) {
            byCenter = Comparator.comparingDouble(entry -> entry.bounds().minX() + entry.bounds().maxX());
        } else if (y >= z) {
            byCenter = Comparator.comparingDouble(entry -> entry.bounds().minY() + entry.bounds().maxY());
        } else {
            byCenter = Comparator.comparingDouble(entry -> entry.bounds().minZ() + entry.bounds().maxZ());
        }
        Arrays.sort(entries, first, end, byCenter);
        int middle = first + (end - first) / 2;
        nodes.add(null);
        int left = build(first, middle, index);
        int right = build(middle, end, index);
        nodes.set(index, new Node(bounds, power, left, right, parent, false));
        return index;
    }
}
