package ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller;

import ca.spottedleaf.moonrise.patches.chunk_system.io.*;
import ca.spottedleaf.moonrise.patches.chunk_system.level.storage.*;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.storage.*;

import java.io.*;

public final class PoiDataController extends MoonriseRegionFileIO.RegionDataController {

    private final ServerLevel world;

    public PoiDataController(final ServerLevel world, final ChunkTaskScheduler taskScheduler) {
        super(MoonriseRegionFileIO.RegionFileType.POI_DATA, taskScheduler.ioExecutor, taskScheduler.compressionExecutor);
        this.world = world;
    }

    @Override
    public RegionFileStorage getCache() {
        return ((ChunkSystemSectionStorage)this.world.getPoiManager()).moonrise$getRegionStorage();
    }

    @Override
    public WriteData startWrite(final int chunkX, final int chunkZ, final CompoundTag compound) throws IOException {
        return ((ChunkSystemRegionFileStorage)this.getCache()).moonrise$startWrite(chunkX, chunkZ, compound);
    }

    @Override
    public void finishWrite(final int chunkX, final int chunkZ, final WriteData writeData) throws IOException {
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
