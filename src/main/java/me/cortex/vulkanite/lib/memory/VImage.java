package me.cortex.vulkanite.lib.memory;

import java.lang.ref.Cleaner;

public class VImage {
    protected VmaAllocator.ImageAllocation allocation;
    public final int width;
    public final int height;
    public final int depth;
    public final int mipLayers;
    public final int format;

    public final int dimensions;

    VImage(VmaAllocator.ImageAllocation allocation, int width, int height, int depth, int mipLayers, int format) {
        this.allocation = allocation;
        this.width = width;
        this.height = height;
        this.mipLayers = mipLayers;
        this.format = format;
        this.depth = depth;

        int dimensions = 3;

        if (height == 1 && depth == 1) {
            dimensions = 1;
        }
        else if(height != 1 && depth == 1) {
            dimensions = 2;
        }

        this.dimensions = dimensions;
    }

    public void free() {
        allocation.free();
        allocation = null;
    }

    public long image() {
        return allocation.image;
    }
}
