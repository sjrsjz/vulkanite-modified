package me.cortex.vulkanite.lib.other;

import net.irisshaders.iris.gl.texture.InternalTextureFormat;

import static org.lwjgl.opengl.GL11C.GL_RGB8;
import static org.lwjgl.opengl.GL11C.GL_RGBA16;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.vulkan.VK10.*;

public class FormatConverter {
    public static int getVkFormatFromGl(InternalTextureFormat format) {
        return switch (format.getGlFormat()) {
            case GL_R11F_G11F_B10F -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;

            case GL_RGB32F, GL_RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case GL_RG32F -> VK_FORMAT_R32G32_SFLOAT;
            case GL_R32F -> VK_FORMAT_R32_SFLOAT;
            case GL_RGB16F, GL_RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL_RG16F -> VK_FORMAT_R16G16_SFLOAT;
            case GL_R16F -> VK_FORMAT_R16_SFLOAT;

            case GL_RGB16, GL_RGBA16 -> VK_FORMAT_R16G16B16A16_UNORM;
            case GL_RG16 -> VK_FORMAT_R16G16_UNORM;
            case GL_R16 -> VK_FORMAT_R16_UNORM;
            case GL_RGBA, GL_RGB8, GL_RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case GL_RG8 -> VK_FORMAT_R8G8_UNORM;
            case GL_R8 -> VK_FORMAT_R8_UNORM;
            case GL_R8I -> VK_FORMAT_R8_SINT;
            case GL_R8UI -> VK_FORMAT_R8_UINT;
            case GL_RG8I -> VK_FORMAT_R8G8_SINT;
            case GL_RG8UI -> VK_FORMAT_R8G8_UINT;
            case GL_RGB8I, GL_RGBA8I -> VK_FORMAT_R8G8B8A8_SINT;
            case GL_RGB8UI, GL_RGBA8UI -> VK_FORMAT_R8G8B8A8_UINT;
            case GL_R16I -> VK_FORMAT_R16_SINT;
            case GL_R16UI -> VK_FORMAT_R16_UINT;
            case GL_RG16I -> VK_FORMAT_R16G16_SINT;
            case GL_RG16UI -> VK_FORMAT_R16G16_UINT;
            case GL_RGB16I, GL_RGBA16I -> VK_FORMAT_R16G16B16A16_SINT;
            case GL_RGB16UI, GL_RGBA16UI -> VK_FORMAT_R16G16B16A16_UINT;
            case GL_R32I -> VK_FORMAT_R32_SINT;
            case GL_R32UI -> VK_FORMAT_R32_UINT;
            case GL_RG32I -> VK_FORMAT_R32G32_SINT;
            case GL_RG32UI -> VK_FORMAT_R32G32_UINT;
            case GL_RGB32I, GL_RGBA32I -> VK_FORMAT_R32G32B32A32_SINT;
            case GL_RGB32UI, GL_RGBA32UI -> VK_FORMAT_R32G32B32A32_UINT;
            case org.lwjgl.opengl.GL31C.GL_R8_SNORM -> VK_FORMAT_R8_SNORM;
            case org.lwjgl.opengl.GL31C.GL_RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case org.lwjgl.opengl.GL31C.GL_RGB8_SNORM, org.lwjgl.opengl.GL31C.GL_RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case org.lwjgl.opengl.GL31C.GL_R16_SNORM -> VK_FORMAT_R16_SNORM;
            case org.lwjgl.opengl.GL31C.GL_RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case org.lwjgl.opengl.GL31C.GL_RGB16_SNORM, org.lwjgl.opengl.GL31C.GL_RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;

            default -> {
                throw new IllegalArgumentException("No known conversion to VK type for GL type " + format + " ("
                        + format.getGlFormat() + ").");
            }
        };
    }

    /**
     * Returns the GL internal format backed by the Vulkan format above. Vulkan
     * portability for three-component formats is poor, so those are expanded to
     * four components while preserving sampled RGB values.
     */
    public static int getSharedGlFormat(InternalTextureFormat format) {
        return switch (format.getGlFormat()) {
            case GL_RGBA, GL_RGB8 -> GL_RGBA8;
            case GL_RGB16 -> GL_RGBA16;
            case GL_RGB16F -> GL_RGBA16F;
            case GL_RGB32F -> GL_RGBA32F;
            case GL_RGB8I -> GL_RGBA8I;
            case GL_RGB8UI -> GL_RGBA8UI;
            case GL_RGB16I -> GL_RGBA16I;
            case GL_RGB16UI -> GL_RGBA16UI;
            case GL_RGB32I -> GL_RGBA32I;
            case GL_RGB32UI -> GL_RGBA32UI;
            case org.lwjgl.opengl.GL31C.GL_RGB8_SNORM -> org.lwjgl.opengl.GL31C.GL_RGBA8_SNORM;
            case org.lwjgl.opengl.GL31C.GL_RGB16_SNORM -> org.lwjgl.opengl.GL31C.GL_RGBA16_SNORM;
            default -> format.getGlFormat();
        };
    }

    public static InternalTextureFormat findFormatFromGlFormat(int glFormat) {
        InternalTextureFormat[] availableFormats = InternalTextureFormat.values();

        for (InternalTextureFormat format : availableFormats) {
            if (format.getGlFormat() == glFormat) {
                return format;
            }
        }

        return null;
    }
}
