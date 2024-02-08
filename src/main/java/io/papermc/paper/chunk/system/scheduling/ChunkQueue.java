package io.papermc.paper.chunk.system.scheduling;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkQueue {

    public final int coordinateShift;
    private final AtomicLong orderGenerator = new AtomicLong();
    private final ConcurrentHashMap<Coordinate, UnloadSection> unloadSections = new ConcurrentHashMap<>();

    /*
     * Note: write operations do not occur in parallel for any given section.
     * Note: coordinateShift <= region shift in order for retrieveForCurrentRegion() to function correctly
     */

    public ChunkQueue(final int coordinateShift) {
        this.coordinateShift = coordinateShift;
    }

    public static record SectionToUnload(int sectionX, int sectionZ, Coordinate coord, long order, int count) {}

    public List<SectionToUnload> retrieveForAllRegions() {
        final List<SectionToUnload> ret = new ArrayList<>();

        for (final Map.Entry<Coordinate, UnloadSection> entry : this.unloadSections.entrySet()) {
            final Coordinate coord = entry.getKey();
            final long key = coord.key;
            final UnloadSection section = entry.getValue();
            final int sectionX = Coordinate.x(key);
            final int sectionZ = Coordinate.z(key);

            ret.add(new SectionToUnload(sectionX, sectionZ, coord, section.order, section.chunks.size()));
        }

        ret.sort((final SectionToUnload s1, final SectionToUnload s2) -> {
            return Long.compare(s1.order, s2.order);
        });

        return ret;
    }

    public UnloadSection getSectionUnsynchronized(final int sectionX, final int sectionZ) {
        final Coordinate coordinate = new Coordinate(Coordinate.key(sectionX, sectionZ));
        return this.unloadSections.get(coordinate);
    }

    public UnloadSection removeSection(final int sectionX, final int sectionZ) {
        final Coordinate coordinate = new Coordinate(Coordinate.key(sectionX, sectionZ));
        return this.unloadSections.remove(coordinate);
    }

    // write operation
    public boolean addChunk(final int chunkX, final int chunkZ) {
        final int shift = this.coordinateShift;
        final int sectionX = chunkX >> shift;
        final int sectionZ = chunkZ >> shift;
        final Coordinate coordinate = new Coordinate(Coordinate.key(sectionX, sectionZ));
        final long chunkKey = Coordinate.key(chunkX, chunkZ);

        UnloadSection section = this.unloadSections.get(coordinate);
        if (section == null) {
            section = new UnloadSection(this.orderGenerator.getAndIncrement());
            // write operations do not occur in parallel for a given section
            this.unloadSections.put(coordinate, section);
        }

        return section.chunks.add(chunkKey);
    }

    // write operation
    public boolean removeChunk(final int chunkX, final int chunkZ) {
        final int shift = this.coordinateShift;
        final int sectionX = chunkX >> shift;
        final int sectionZ = chunkZ >> shift;
        final Coordinate coordinate = new Coordinate(Coordinate.key(sectionX, sectionZ));
        final long chunkKey = Coordinate.key(chunkX, chunkZ);

        final UnloadSection section = this.unloadSections.get(coordinate);

        if (section == null) {
            return false;
        }

        if (!section.chunks.remove(chunkKey)) {
            return false;
        }

        if (section.chunks.isEmpty()) {
            this.unloadSections.remove(coordinate);
        }

        return true;
    }

    public static final class UnloadSection {

        public final long order;
        public final LongLinkedOpenHashSet chunks = new LongLinkedOpenHashSet();

        public UnloadSection(final long order) {
            this.order = order;
        }
    }

    private static final class Coordinate implements Comparable<Coordinate> {

        public final long key;

        public Coordinate(final long key) {
            this.key = key;
        }

        public Coordinate(final int x, final int z) {
            this.key = key(x, z);
        }

        public static long key(final int x, final int z) {
            return ((long)z << 32) | (x & 0xFFFFFFFFL);
        }

        public static int x(final long key) {
            return (int)key;
        }

        public static int z(final long key) {
            return (int)(key >>> 32);
        }

        @Override
        public int hashCode() {
            return (int)HashCommon.mix(this.key);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof Coordinate other)) {
                return false;
            }

            return this.key == other.key;
        }

        // This class is intended for HashMap/ConcurrentHashMap usage, which do treeify bin nodes if the chain
        // is too large. So we should implement compareTo to help.
        @Override
        public int compareTo(final Coordinate other) {
            return Long.compare(this.key, other.key);
        }
    }
}
