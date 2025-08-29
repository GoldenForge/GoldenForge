package ca.spottedleaf.moonrise.patches.chunk_system.level;

import net.minecraft.world.level.*;

import java.io.*;

public interface ChunkSystemChunkMap {

    public void moonrise$writeFinishCallback(final ChunkPos pos) throws IOException;

}
