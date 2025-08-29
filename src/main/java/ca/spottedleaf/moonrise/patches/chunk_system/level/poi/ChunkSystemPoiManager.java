package ca.spottedleaf.moonrise.patches.chunk_system.level.poi;

import ca.spottedleaf.moonrise.patches.chunk_system.level.storage.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.*;

public interface ChunkSystemPoiManager extends ChunkSystemSectionStorage {

    public ServerLevel moonrise$getWorld();

    public void moonrise$onUnload(final long coordinate);

    public void moonrise$loadInPoiChunk(final PoiChunk poiChunk);

    public void moonrise$checkConsistency(final ChunkAccess chunk);

}
