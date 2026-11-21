package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.FormatConverter;
import me.cortex.vulkanite.lib.other.VSampler;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL33C.glGetSamplerParameteri;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.vulkan.VK10.*;

/** Preserves Iris sampling settings and also supports dynamic TextureWrapper/lightmap sources. */
public final class CustomTextureBinding {
    private final VContext ctx;
    private final TextureAccess access;
    private VGImage copy;
    private VImage currentImage;
    public final SharedImageViewTracker view;
    public final VSampler sampler;

    public CustomTextureBinding(VContext ctx, TextureAccess access) {
        this.ctx = ctx;
        this.access = access;
        this.view = new SharedImageViewTracker(ctx, () -> currentImage);
        int glSampler = access.getSampling().getId();
        int mag = glGetSamplerParameteri(glSampler, GL_TEXTURE_MAG_FILTER);
        int min = glGetSamplerParameteri(glSampler, GL_TEXTURE_MIN_FILTER);
        var format = FormatConverter.findFormatFromGlFormat(glGetTextureLevelParameteri(
                access.getTextureId().getAsInt(), 0, GL_TEXTURE_INTERNAL_FORMAT));
        boolean integer = format != null && format.getShaderDataType() != net.irisshaders.iris.gl.texture.ShaderDataType.FLOAT;
        boolean rectangle = access.getType() == TextureType.TEXTURE_RECTANGLE;
        sampler = new VSampler(ctx, info -> info
                .magFilter(integer || mag == GL_NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                .minFilter(integer ? VK_FILTER_NEAREST : rectangle ? (mag == GL_NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR)
                        : (min == GL_NEAREST || min == GL_NEAREST_MIPMAP_NEAREST || min == GL_NEAREST_MIPMAP_LINEAR
                        ? VK_FILTER_NEAREST : VK_FILTER_LINEAR))
                .mipmapMode(!integer && !rectangle && (min == GL_NEAREST_MIPMAP_LINEAR || min == GL_LINEAR_MIPMAP_LINEAR)
                        ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(rectangle ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : address(glGetSamplerParameteri(glSampler, GL_TEXTURE_WRAP_S)))
                .addressModeV(rectangle ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : address(glGetSamplerParameteri(glSampler, GL_TEXTURE_WRAP_T)))
                .addressModeW(address(glGetSamplerParameteri(glSampler, GL_TEXTURE_WRAP_R)))
                .minLod(0).maxLod(!rectangle && min != GL_NEAREST && min != GL_LINEAR ? VK_LOD_CLAMP_NONE : 0)
                .borderColor(integer ? VK_BORDER_COLOR_INT_TRANSPARENT_BLACK : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                .unnormalizedCoordinates(rectangle));
    }

    private static int address(int value) {
        return switch (value) {
            case GL_REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case GL_MIRRORED_REPEAT -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case GL_CLAMP_TO_BORDER -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            default -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
    }

    public void refresh() {
        currentImage = resolveImage();
    }

    private VImage resolveImage() {
        if (access instanceof IVGImage shared) {
            shared.ensureShared();
            var image = shared.getVGImage();
            if (image.isPresent()) return image.get();
        }
        int source = access.getTextureId().getAsInt();
        int target = access.getType().getGlType();
        int width = glGetTextureLevelParameteri(source, 0, GL_TEXTURE_WIDTH);
        int height = access.getType() == TextureType.TEXTURE_1D ? 1 : glGetTextureLevelParameteri(source, 0, GL_TEXTURE_HEIGHT);
        int depth = access.getType() == TextureType.TEXTURE_3D ? glGetTextureLevelParameteri(source, 0, GL_TEXTURE_DEPTH) : 1;
        var format = FormatConverter.findFormatFromGlFormat(glGetTextureLevelParameteri(source, 0, GL_TEXTURE_INTERNAL_FORMAT));
        if (format == null || width < 1 || height < 1 || depth < 1) throw new IllegalStateException("Invalid Iris custom texture: " + source);
        int vkFormat = FormatConverter.getVkFormatFromGl(format);
        if (copy == null || copy.width != width || copy.height != height || copy.depth != depth || copy.format != vkFormat) {
            if (copy != null) {
                VGImage retired = copy;
                Vulkanite.INSTANCE.addSyncedCallback(retired::free);
            }
            copy = ctx.memory.createSharedImage(width, height, depth, 1, vkFormat, FormatConverter.getSharedGlFormat(format),
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, target);
        }
        // Re-resolve and copy wrappers each frame: their GL name/content may change on resource reload.
        if (format.getGlFormat() == copy.glFormat) {
            glCopyImageSubData(source, target, 0, 0, 0, 0, copy.glId, target, 0, 0, 0, 0, width, height, depth);
        } else {
            var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), Math.multiplyExact(depth, 16)));
            try {
                int pixelFormat = format.getShaderDataType() == net.irisshaders.iris.gl.texture.ShaderDataType.FLOAT ? GL_RGBA : GL_RGBA_INTEGER;
                int pixelType = switch (format.getShaderDataType()) {
                    case FLOAT -> GL_FLOAT;
                    case INT -> GL_INT;
                    case UINT -> GL_UNSIGNED_INT;
                };
                glGetTextureImage(source, 0, pixelFormat, pixelType, pixels);
                switch (access.getType()) {
                    case TEXTURE_1D -> glTextureSubImage1D(copy.glId, 0, 0, width, pixelFormat, pixelType, pixels);
                    case TEXTURE_2D, TEXTURE_RECTANGLE -> glTextureSubImage2D(copy.glId, 0, 0, 0, width, height, pixelFormat, pixelType, pixels);
                    case TEXTURE_3D -> glTextureSubImage3D(copy.glId, 0, 0, 0, 0, width, height, depth, pixelFormat, pixelType, pixels);
                }
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
        return copy;
    }

    public void free() {
        view.free();
        if (copy != null) copy.free();
        sampler.free();
    }
}
