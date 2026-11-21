package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGImage;
import java.util.Optional;

public interface IVGImage {
    default void ensureShared() {}
    Optional<VGImage> getVGImage();
    void setVGImage(VGImage image);
}
