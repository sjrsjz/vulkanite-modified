package me.cortex.vulkanite.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeometryEncodingTest {
    @Test public void entityPositionsMatchDirtRtHalfFloatDecoding() {
        for (float position : new float[]{-256, -32, -8, 0, 1.25f, 24, 64, 256}) {
            assertEquals(position, Float.float16ToFloat(GeometryEncoding.entityPosition(position, false)), 0);
        }
    }

    @Test public void terrainAtlasGeometryRetainsFixedPointAbi() {
        for (float position : new float[]{-8, -1, 0, 1.25f, 16}) {
            float decoded = Short.toUnsignedInt(GeometryEncoding.entityPosition(position, true)) / 2048f - 8;
            assertEquals(position, decoded, 0);
        }
    }
}
