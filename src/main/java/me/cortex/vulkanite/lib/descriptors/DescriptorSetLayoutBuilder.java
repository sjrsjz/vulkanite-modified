package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;

public final class DescriptorSetLayoutBuilder {
    private record Binding(int index, int type, int count, int stages) {}

    private final List<Binding> bindings = new ArrayList<>();
    private final Map<Integer, Integer> bindingFlags = new HashMap<>();
    private final int flags;

    public DescriptorSetLayoutBuilder() {
        this(0);
    }

    public DescriptorSetLayoutBuilder(int flags) {
        this.flags = flags;
    }

    public DescriptorSetLayoutBuilder binding(int index, int type, int count, int stages) {
        bindings.add(new Binding(index, type, count, stages));
        return this;
    }

    public DescriptorSetLayoutBuilder binding(int index, int type, int stages) {
        return binding(index, type, 1, stages);
    }

    public DescriptorSetLayoutBuilder setBindingFlags(int binding, int flags) {
        bindingFlags.put(binding, flags);
        return this;
    }

    public VDescriptorSetLayout build(VContext context) {
        try (var stack = stackPush()) {
            var nativeBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            var nativeFlags = stack.callocInt(bindings.size());
            for (int i = 0; i < bindings.size(); i++) {
                var binding = bindings.get(i);
                nativeBindings.get(i).set(binding.index(), binding.type(), binding.count(), binding.stages(), null);
                nativeFlags.put(i, bindingFlags.getOrDefault(binding.index(), 0));
            }
            var info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().flags(flags).pBindings(nativeBindings);
            if (!bindingFlags.isEmpty()) {
                info.pNext(VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack).sType$Default().pBindingFlags(nativeFlags));
            }

            var result = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorSetLayout(context.device, info, null, result), "Create Vulkanite descriptor layout");
            return new VDescriptorSetLayout(context, result.get(0), bindings.stream().mapToInt(Binding::type).toArray());
        }
    }
}
