package me.cortex.vulkanite.compat;

/** Positions in Dirt RT's 40-byte vertex ABI. */
public final class GeometryEncoding {
    private GeometryEncoding() {}

    public static short entityPosition(float position, boolean blockAtlas) {
        return blockAtlas ? (short) Math.round((position + 8) * 2048) : Float.floatToFloat16(position);
    }
}
