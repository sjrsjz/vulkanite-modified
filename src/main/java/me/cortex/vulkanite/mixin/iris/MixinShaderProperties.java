package me.cortex.vulkanite.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.cortex.vulkanite.compat.RaytracingProperties;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderProperties.class, remap = false)
public class MixinShaderProperties implements RaytracingProperties {
    @org.spongepowered.asm.mixin.Unique
    private java.util.Properties vulkanite$properties;

    @ModifyExpressionValue(method = "<init>(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)V",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/preprocessor/PropertiesPreprocessor;preprocessSource(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)Ljava/lang/String;"))
    private String vulkanite$captureProperties(String contents) {
        vulkanite$properties = new java.util.Properties();
        try {
            vulkanite$properties.load(new java.io.StringReader(contents));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot parse RT shader properties", e);
        }
        return contents;
    }

    @Override
    public java.util.Properties getRaytracingProperties() {
        return vulkanite$properties != null ? vulkanite$properties : new java.util.Properties();
    }
}
