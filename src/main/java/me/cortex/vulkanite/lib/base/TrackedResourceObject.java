package me.cortex.vulkanite.lib.base;

import java.lang.ref.Cleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TrackedResourceObject {

    private final Ref ref;
    private static Logger LOGGER = LoggerFactory.getLogger(TrackedResourceObject.class.getName());

    public TrackedResourceObject() {
        this.ref = register(this);
    }

    protected void free0() {
        ref.freedRef[0] = true;
        ref.cleanable.clean();
    }

    public abstract void free();

    public boolean isFreed() {
        return ref.freedRef[0];
    }

    private record Ref(Cleaner.Cleanable cleanable, boolean[] freedRef) {
    }

    private static final Cleaner cleaner = Cleaner.create();

    public static Ref register(Object obj) {
        String clazz = obj.getClass().getName();
        Throwable trace = me.cortex.vulkanite.client.Vulkanite.MEMORY_LEAK_TRACING ? new Throwable() : null;
        boolean[] freed = new boolean[1];
        var clean = cleaner.register(obj, () -> {
            if (!freed[0] && trace != null) {
                LOGGER.warn("Object named: " + clazz + " was not freed, location at: ");
                trace.printStackTrace();
            }
        });
        return new Ref(clean, freed);
    }
}
