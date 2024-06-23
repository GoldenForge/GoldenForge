package io.papermc.paper.chunk.system.scheduling;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkQueue {

    public final int coordinateShift;
    private final AtomicLong orderGenerator = new AtomicLong();
    private final ConcurrentLong2ReferenceChainedHashTable<UnloadSection> unloadSections = new ConcurrentLong2ReferenceChainedHashTable<>();

    /*
     * Note: write operations do not occur in parallel for any given section.
     * Note: coordinateShift <= region shift in order for retrieveForCurrentRegion() to function correctly
     */

    public ChunkQueue(final int coordinateShift) {
        this.coordinateShift = coordinateShift;
    }

    public static record SectionToUnload(int sectionX, int sectionZ, long order, int count) {}

    public List<SectionToUnload> retrieveForAllRegions() {
        final List<SectionToUnload> ret = new ArrayList<>();

        for (final Iterator<ConcurrentLong2ReferenceChainedHashTable.TableEntry<UnloadSection>> iterator = this.unloadSections.entryIterator(); iterator.hasNext();) {
            final ConcurrentLong2ReferenceChainedHashTable.TableEntry<UnloadSection> entry = iterator.next();
            final long key = entry.getKey();
            final UnloadSection section = entry.getValue();
            final int sectionX = Coordinate.x(key);
            final int sectionZ = Coordinate.z(key);

            ret.add(new SectionToUnload(sectionX, sectionZ, section.order, section.chunks.size()));
        }

        ret.sort((final SectionToUnload s1, final SectionToUnload s2) -> {
            return Long.compare(s1.order, s2.order);
        });

        return ret;
    }

    public UnloadSection getSectionUnsynchronized(final int sectionX, final int sectionZ) {
        return this.unloadSections.get(CoordinateUtils.getChunkKey(sectionX, sectionZ));
    }

    public UnloadSection removeSection(final int sectionX, final int sectionZ) {
        return this.unloadSections.remove(CoordinateUtils.getChunkKey(sectionX, sectionZ));
    }

    // write operation
    public boolean addChunk(final int chunkX, final int chunkZ) {
        // write operations do not occur in parallel for a given section
        final int shift = this.coordinateShift;
        final int sectionX = chunkX >> shift;
        final int sectionZ = chunkZ >> shift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        UnloadSection section = this.unloadSections.get(sectionKey);
        if (section == null) {
            section = new UnloadSection(this.orderGenerator.getAndIncrement());
            this.unloadSections.put(sectionKey, section);
        }

        return section.chunks.add(chunkKey);
    }

    // write operation
    public boolean removeChunk(final int chunkX, final int chunkZ) {
        // write operations do not occur in parallel for a given section
        final int shift = this.coordinateShift;
        final int sectionX = chunkX >> shift;
        final int sectionZ = chunkZ >> shift;
        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final UnloadSection section = this.unloadSections.get(sectionKey);

        if (section == null) {
            return false;
        }

        if (!section.chunks.remove(chunkKey)) {
            return false;
        }

        if (section.chunks.isEmpty()) {
            this.unloadSections.remove(sectionKey);
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
