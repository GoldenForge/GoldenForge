package ca.spottedleaf.moonrise.patches.block_counting;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.shorts.*;

public interface BlockCountingBitStorage {

    public Int2ObjectOpenHashMap<ShortArrayList> moonrise$countEntries();

}
