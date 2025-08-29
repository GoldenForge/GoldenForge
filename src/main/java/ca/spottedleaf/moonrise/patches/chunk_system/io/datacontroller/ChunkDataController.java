package ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller;

import ca.spottedleaf.moonrise.patches.chunk_system.io.*;
import ca.spottedleaf.moonrise.patches.chunk_system.level.*;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.*;
import ca.spottedleaf.moonrise.patches.chunk_system.storage.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.storage.*;

import java.io.*;

public final class ChunkDataController extends MoonriseRegionFileIO.RegionDataController {

    private final ServerLevel world;

    public ChunkDataController(final ServerLevel world, final ChunkTaskScheduler taskScheduler) {
        super(MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, taskScheduler.ioExecutor, taskScheduler.compressionExecutor);
        this.world = world;
    }

    @Override
    public RegionFileStorage getCache() {
        return ((ChunkSystemChunkStorage)this.world.getChunkSource().chunkMap).moonrise$getRegionStorage();
    }

    @Override
    public WriteData startWrite(final int chunkX, final int chunkZ, final CompoundTag compound) throws IOException {
        return ((ChunkSystemRegionFileStorage)this.getCache()).moonrise$startWrite(chunkX, chunkZ, compound);
    }

    @Override
    public void finishWrite(final int chunkX, final int chunkZ, final WriteData writeData) throws IOException {
        ((ChunkSystemChunkMap)this.world.getChunkSource().chunkMap).moonrise$writeFinishCallback(new ChunkPos(chunkX, chunkZ));
        ((ChunkSystemRegionFileStorage)this.getCache()).moonrise$finishWrite(chunkX, chunkZ, writeData);
    }

    @Override
    public ReadData readData(final int chunkX, final int chunkZ) throws IOException {
        return ((ChunkSystemRegionFileStorage)this.getCache()).moonrise$readData(chunkX, chunkZ);
    }

    @Override
    public CompoundTag finishRead(final int chunkX, final int chunkZ, final ReadData readData) throws IOException {
        return ((ChunkSystemRegionFileStorage)this.getCache()).moonrise$finishRead(chunkX, chunkZ, readData);
    }
}
