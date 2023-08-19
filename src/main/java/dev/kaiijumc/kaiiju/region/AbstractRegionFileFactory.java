package dev.kaiijumc.kaiiju.region;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.IOException;
import java.nio.file.Path;

public class AbstractRegionFileFactory {
    public static AbstractRegionFile getAbstractRegionFile(int linearCompression, Path file, Path directory, boolean dsync) throws IOException {
        return getAbstractRegionFile(linearCompression, file, directory, RegionFileVersion.VERSION_DEFLATE, dsync);
    }

    public static AbstractRegionFile getAbstractRegionFile(int linearCompression, Path file, Path directory, boolean dsync, boolean canRecalcHeader) throws IOException {
        return getAbstractRegionFile(linearCompression, file, directory, RegionFileVersion.VERSION_DEFLATE, dsync, canRecalcHeader);
    }

    public static AbstractRegionFile getAbstractRegionFile(int linearCompression, Path file, Path directory, RegionFileVersion outputChunkStreamVersion, boolean dsync) throws IOException {
        return getAbstractRegionFile(linearCompression, file, directory, outputChunkStreamVersion, dsync, false);
    }

    public static AbstractRegionFile getAbstractRegionFile(int linearCompression, Path file, Path directory, RegionFileVersion outputChunkStreamVersion, boolean dsync, boolean canRecalcHeader) throws IOException {
        if (file.toString().endsWith(".linear")) {
            return new LinearRegionFile(file, linearCompression);
        } else {
            return new RegionFile(file, directory, outputChunkStreamVersion, dsync, canRecalcHeader);
        }
    }
}
