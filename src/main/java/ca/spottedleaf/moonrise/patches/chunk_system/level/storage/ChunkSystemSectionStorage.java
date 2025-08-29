package ca.spottedleaf.moonrise.patches.chunk_system.level.storage;

import net.minecraft.world.level.chunk.storage.*;

import java.io.*;

public interface ChunkSystemSectionStorage {

    public RegionFileStorage moonrise$getRegionStorage();

    public void moonrise$close() throws IOException;

}
