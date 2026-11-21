package me.cortex.vulkanite.acceleration;

import java.util.IdentityHashMap;
import java.util.Map;

/** Versions accepted mesh updates by section identity, including empty updates. */
public final class SectionBuildState<S> {
    private final Map<S, Long> versions = new IdentityHashMap<>();
    private long nextVersion;

    public synchronized long accept(S section) {
        long version = ++nextVersion;
        versions.put(section, version);
        return version;
    }

    public synchronized boolean isCurrent(S section, long version) {
        Long current = versions.get(section);
        return current != null && current == version;
    }

    public synchronized void remove(S section) {
        versions.remove(section);
    }

    public synchronized void clear() {
        versions.clear();
    }
}
