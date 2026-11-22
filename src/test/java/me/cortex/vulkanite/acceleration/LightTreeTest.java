package me.cortex.vulkanite.acceleration;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class LightTreeTest {
    @Test
    public void preservesBoundsPowerAndLeafReferencesAtTerrainScale() {
        var sections = new ArrayList<LightTree.Entry<Integer>>();
        int total = 0;
        for (int section = 0; section < 750; section++) {
            var lights = new ArrayList<LightTree.Entry<Integer>>();
            for (int i = 0; i < 400; i++) {
                // Repeated centroids also model overlapping and inset block models.
                float x = (i / 4) % 10;
                float z = i / 40;
                var bounds = new LightTree.Bounds(x, 0, z, x + 1, 1, z + 1);
                lights.add(new LightTree.Entry<>(bounds, 1.0f, i));
            }
            var local = new LightTree<>(lights, 4);
            assertEquals(400.0f, local.nodes.getFirst().power(), 0.0f);
            int count = verify(local, 0, -1, 4);
            total += count;
            var root = local.nodes.getFirst();
            var worldBounds = root.bounds().translated((section % 30) * 16, 0, (section / 30) * 16);
            sections.add(new LightTree.Entry<>(worldBounds, root.power(), section));
        }
        var top = new LightTree<>(sections, 1);
        assertEquals(300_000, total);
        assertEquals(750, verify(top, 0, -1, 1));
        assertEquals(300_000.0f, top.nodes.getFirst().power(), 0.0f);
    }

    @Test
    public void representsAnEmptySceneWithoutAChildPointer() {
        var tree = new LightTree<Integer>(java.util.List.of(), 4);
        assertTrue(tree.nodes.isEmpty());
        assertEquals(0, tree.entries.length);
    }

    private static int verify(LightTree<?> tree, int index, int parent, int capacity) {
        var node = tree.nodes.get(index);
        assertEquals(parent, node.parent());
        if (node.leaf()) {
            assertTrue(node.right() > 0 && node.right() <= capacity);
            for (int i = node.left(); i < node.left() + node.right(); i++) {
                assertEquals(node.bounds(), node.bounds().union(tree.entries[i].bounds()));
            }
            return node.right();
        }
        var left = tree.nodes.get(node.left());
        var right = tree.nodes.get(node.right());
        assertEquals(node.bounds(), left.bounds().union(right.bounds()));
        assertEquals(node.power(), left.power() + right.power(), 0.0f);
        return verify(tree, node.left(), index, capacity) + verify(tree, node.right(), index, capacity);
    }
}
