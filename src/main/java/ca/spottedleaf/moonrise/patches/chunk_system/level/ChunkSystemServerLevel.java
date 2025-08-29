package ca.spottedleaf.moonrise.patches.chunk_system.level;

import ca.spottedleaf.concurrentutil.util.*;
import ca.spottedleaf.moonrise.common.list.*;
import ca.spottedleaf.moonrise.common.misc.*;
import ca.spottedleaf.moonrise.patches.chunk_system.io.*;
import ca.spottedleaf.moonrise.patches.chunk_system.player.*;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.*;
import net.minecraft.core.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;

import java.util.*;
import java.util.function.*;

public interface ChunkSystemServerLevel extends ChunkSystemLevel {

    public ChunkTaskScheduler moonrise$getChunkTaskScheduler();

    public MoonriseRegionFileIO.RegionDataController moonrise$getChunkDataController();

    public MoonriseRegionFileIO.RegionDataController moonrise$getPoiChunkDataController();

    public MoonriseRegionFileIO.RegionDataController moonrise$getEntityChunkDataController();

    public int moonrise$getRegionChunkShift();

    // Paper

    public RegionizedPlayerChunkLoader moonrise$getPlayerChunkLoader();

    public void moonrise$loadChunksAsync(final BlockPos pos, final int radiusBlocks,
                                         final Priority priority,
                                         final Consumer<List<ChunkAccess>> onLoad);

    public void moonrise$loadChunksAsync(final BlockPos pos, final int radiusBlocks,
                                         final ChunkStatus chunkStatus, final Priority priority,
                                         final Consumer<List<ChunkAccess>> onLoad);

    public void moonrise$loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                         final Priority priority,
                                         final Consumer<List<ChunkAccess>> onLoad);

    public void moonrise$loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                         final ChunkStatus chunkStatus, final Priority priority,
                                         final Consumer<List<ChunkAccess>> onLoad);

    public RegionizedPlayerChunkLoader.ViewDistanceHolder moonrise$getViewDistanceHolder();

    public long moonrise$getLastMidTickFailure();

    public void moonrise$setLastMidTickFailure(final long time);

    public NearbyPlayers moonrise$getNearbyPlayers();

    public ReferenceList<LevelChunk> moonrise$getLoadedChunks();

    public ReferenceList<LevelChunk> moonrise$getTickingChunks();

    public ReferenceList<LevelChunk> moonrise$getEntityTickingChunks();
}
