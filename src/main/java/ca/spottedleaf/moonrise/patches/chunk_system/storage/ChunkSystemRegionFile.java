package ca.spottedleaf.moonrise.patches.chunk_system.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.*;

import java.io.*;

public interface ChunkSystemRegionFile {

    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(final CompoundTag data, final ChunkPos pos) throws IOException;

}
