package gg.airplane.structs;

import it.unimi.dsi.fastutil.HashCommon;

/**
 * A replacement for the cache used in Biome.
 */
public class Long2FloatAgingCache {

    private static class AgingEntry {
        private long data;
        private float value;
        private int uses = 0;
        private int age = 0;

        private AgingEntry(long data, float value) {
            this.data = data;
            this.value = value;
        }

        public void replace(long data, float flag) {
            this.data = data;
            this.value = flag;
        }

        public int getValue() {
            return this.uses - (this.age >> 1); // age isn't as important as uses
        }

        public void incrementUses() {
            this.uses = this.uses + 1 & Integer.MAX_VALUE;
        }

        public void incrementAge() {
            this.age = this.age + 1 & Integer.MAX_VALUE;
        }
    }

    private final AgingEntry[] entries;
    private final int mask;
    private final int maxDistance; // the most amount of entries to check for a value

    public Long2FloatAgingCache(int size) {
        int arraySize = HashCommon.nextPowerOfTwo(size);
        this.entries = new AgingEntry[arraySize];
        this.mask = arraySize - 1;
        this.maxDistance = Math.min(arraySize, 4);
    }

    public float getValue(long data) {
        AgingEntry curr;
        int pos;

        if ((curr = this.entries[pos = HashCommon.mix(HashCommon.long2int(data)) & this.mask]) == null) {
            return Float.NaN;
        } else if (data == curr.data) {
            curr.incrementUses();
            return curr.value;
        }

        int checked = 1; // start at 1 because we already checked the first spot above

        while ((curr = this.entries[pos = (pos + 1) & this.mask]) != null) {
            if (data == curr.data) {
                curr.incrementUses();
                return curr.value;
            } else if (++checked >= this.maxDistance) {
                break;
            }
        }

        return Float.NaN;
    }

    public void putValue(long data, float value) {
        AgingEntry curr;
        int pos;

        if ((curr = this.entries[pos = HashCommon.mix(HashCommon.long2int(data)) & this.mask]) == null) {
            this.entries[pos] = new AgingEntry(data, value); // add
            return;
        } else if (data == curr.data) {
            curr.incrementUses();
            return;
        }

        int checked = 1; // start at 1 because we already checked the first spot above

        while ((curr = this.entries[pos = (pos + 1) & this.mask]) != null) {
            if (data == curr.data) {
                curr.incrementUses();
                return;
            } else if (++checked >= this.maxDistance) {
                this.forceAdd(data, value);
                return;
            }
        }

        this.entries[pos] = new AgingEntry(data, value); // add
    }

    private void forceAdd(long data, float value) {
        int expectedPos = HashCommon.mix(HashCommon.long2int(data)) & this.mask;
        AgingEntry entryToRemove = this.entries[expectedPos];

        for (int i = expectedPos + 1; i < expectedPos + this.maxDistance; i++) {
            int pos = i & this.mask;
            AgingEntry entry = this.entries[pos];
            if (entry.getValue() < entryToRemove.getValue()) {
                entryToRemove = entry;
            }

            entry.incrementAge(); // use this as a mechanism to age the other entries
        }

        // remove the least used/oldest entry
        entryToRemove.replace(data, value);
    }
}
