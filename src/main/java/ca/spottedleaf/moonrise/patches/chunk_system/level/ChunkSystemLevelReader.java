package ca.spottedleaf.moonrise.patches.chunk_system.level;

import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;

public interface ChunkSystemLevelReader {

    public ChunkAccess moonrise$syncLoadNonFull(final int chunkX, final int chunkZ, final ChunkStatus status);

}
