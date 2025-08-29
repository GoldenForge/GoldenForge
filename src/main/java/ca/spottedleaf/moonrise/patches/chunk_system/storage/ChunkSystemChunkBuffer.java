package ca.spottedleaf.moonrise.patches.chunk_system.storage;

import net.minecraft.world.level.chunk.storage.*;

import java.io.*;

public interface ChunkSystemChunkBuffer {
    public boolean moonrise$getWriteOnClose();

    public void moonrise$setWriteOnClose(final boolean value);

    public void moonrise$write(final RegionFile regionFile) throws IOException;
}
