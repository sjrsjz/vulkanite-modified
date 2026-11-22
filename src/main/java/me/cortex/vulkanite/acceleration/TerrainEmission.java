package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.mixin.minecraft.SpriteImageAccessor;
import net.irisshaders.iris.mixin.texture.TextureAtlasAccessor;
import net.irisshaders.iris.pbr.texture.SpriteContentsExtension;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.ArrayList;
import java.util.List;

/** An atlas lookup for the light-selection weights of LabPBR emissive sprites. */
final class TerrainEmission {
    private static final int CELL_BITS = 5;
    private static final int CELLS = 1 << CELL_BITS;
    private static final int CELL_SHIFT = 16 - CELL_BITS;

    private record Tile(int minU, int minV, int maxU, int maxV, float weight) {}

    private final List<List<Tile>> cells = new ArrayList<>(CELLS * CELLS);
    private boolean empty = true;

    TerrainEmission(TextureAtlas atlas) {
        for (int i = 0; i < CELLS * CELLS; i++) cells.add(new ArrayList<>());
        if (atlas == null) return;
        for (var sprite : ((TextureAtlasAccessor) atlas).getTexturesByName().values()) {
            var holder = ((SpriteContentsExtension) sprite.contents()).getPBRHolder();
            if (holder == null || holder.getSpecularSprite() == null) continue;
            var contents = holder.getSpecularSprite().contents();
            var image = ((SpriteImageAccessor) contents).vulkanite$getOriginalImage();
            long emission = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getPixel(x, y) >>> 24;
                    if (alpha < 255) emission += alpha;
                }
            }
            if (emission == 0) continue;
            float weight = (float) (emission / (254.0 * image.getWidth() * image.getHeight()));
            add(new Tile(uv(sprite.getU0()), uv(sprite.getV0()), uv(sprite.getU1()), uv(sprite.getV1()), weight));
            empty = false;
        }
    }

    private static int uv(float value) {
        return Math.clamp((int) (value * 65536.0f), 0, 65535);
    }

    private void add(Tile tile) {
        for (int y = tile.minV() >> CELL_SHIFT; y <= tile.maxV() >> CELL_SHIFT; y++) {
            for (int x = tile.minU() >> CELL_SHIFT; x <= tile.maxU() >> CELL_SHIFT; x++) {
                cells.get(y * CELLS + x).add(tile);
            }
        }
    }

    float weight(int u, int v) {
        for (var tile : cells.get((v >> CELL_SHIFT) * CELLS + (u >> CELL_SHIFT))) {
            if (u >= tile.minU() && u <= tile.maxU() && v >= tile.minV() && v <= tile.maxV()) {
                return tile.weight();
            }
        }
        return 0.0f;
    }

    boolean isEmpty() {
        return empty;
    }
}
