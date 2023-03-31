package ca.spottedleaf.leafprofiler;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class LProfilerRegistry {

    // volatile required to ensure correct publishing when resizing
    private volatile ProfilerEntry[] typesById = new ProfilerEntry[16];
    private int totalEntries;
    private final ConcurrentHashMap<String, ProfilerEntry> nameToEntry = new ConcurrentHashMap<>();

    public LProfilerRegistry() {

    }

    public ProfilerEntry getById(final int id) {
        final ProfilerEntry[] entries = this.typesById;

        return id < 0 || id >= entries.length ? null : entries[id];
    }

    public ProfilerEntry getByName(final String name) {
        return this.nameToEntry.get(name);
    }

    public int createType(final ProfileType type, final String name) {
        synchronized (this) {
            final int id = this.totalEntries;

            final ProfilerEntry ret = new ProfilerEntry(type, name, id);

            final ProfilerEntry prev = this.nameToEntry.putIfAbsent(name, ret);

            if (prev != null) {
                throw new IllegalStateException("Entry already exists: " + prev);
            }

            ++this.totalEntries;

            ProfilerEntry[] entries = this.typesById;

            if (id >= entries.length) {
                this.typesById = entries = Arrays.copyOf(entries, entries.length * 2);
            }

            // should be opaque, but I don't think that matters here.
            entries[id] = ret;

            return id;
        }
    }

    public static enum ProfileType {
        TIMER, COUNTER
    }

    public static record ProfilerEntry(ProfileType type, String name, int id) {}
}
