package me.cortex.vulkanite.compat;

import java.util.Properties;

/** Shader-pack settings shared by discovery and regression tests. */
public final class RaytracingSettings {
    private RaytracingSettings() {}

    public static int[] groups(Properties properties, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = i;
        String value = properties.getProperty("rt.groups");
        if (value == null || value.isBlank()) return result;
        String[] parts = value.trim().split("\\s+");
        for (int i = 0; i < Math.min(parts.length, count); i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid rt.groups entry: " + parts[i], e);
            }
        }
        return result;
    }

    public static float[] dispatch(Properties properties, String key) {
        float[] result = {1, 1, 1};
        String value = properties.getProperty(key);
        if (value == null) return result;
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("Invalid " + key + ": " + value);
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i]);
            if (!Float.isFinite(result[i]) || result[i] <= 0) {
                throw new IllegalArgumentException(key + " dimensions must be finite and positive: " + value);
            }
        }
        return result;
    }
}
