package me.cortex.vulkanite.compat;

import org.junit.Test;
import java.util.Properties;
import static org.junit.Assert.*;

public class RaytracingSettingsTest {
    @Test public void sparseGroupListIsPaddedForAllPasses() {
        var properties = new Properties();
        properties.setProperty("rt.groups", "7");
        assertArrayEquals(new int[]{7, 1, 2}, RaytracingSettings.groups(properties, 3));
    }

    @Test public void dirtRtExecutionGroupsArePreserved() {
        var properties = new Properties();
        properties.setProperty("rt.groups", "0 1 1 1 2 3");
        assertArrayEquals(new int[]{0, 1, 1, 1, 2, 3}, RaytracingSettings.groups(properties, 6));
    }

    @Test public void dispatchSupportsRelativeAndFixedDimensions() {
        var properties = new Properties();
        properties.setProperty("rt.0.dispatch", "0.5 1080 64");
        assertArrayEquals(new float[]{0.5f, 1080, 64}, RaytracingSettings.dispatch(properties, "rt.0.dispatch"), 0);
    }

    @Test public void invalidDispatchIsRejectedBeforeGpuSubmission() {
        for (String invalid : new String[]{"NaN 1 1", "Infinity 1 1", "0 1 1", "-1 1 1", "1"}) {
            var properties = new Properties();
            properties.setProperty("rt.0.dispatch", invalid);
            assertThrows(IllegalArgumentException.class, () -> RaytracingSettings.dispatch(properties, "rt.0.dispatch"));
        }
    }
}
