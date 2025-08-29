package ca.spottedleaf.moonrise.patches.chunk_system;

import ca.spottedleaf.moonrise.common.*;
import net.minecraft.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.util.datafix.fixes.*;

public final class ChunkSystemConverters {

    // See SectionStorage#getVersion
    private static final int DEFAULT_POI_DATA_VERSION = 1945;

    private static final int DEFAULT_ENTITY_CHUNK_DATA_VERSION = -1;

    private static int getCurrentVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static int getDataVersion(final CompoundTag data, final int dfl) {
        return data.getInt(SharedConstants.DATA_VERSION_TAG);
    }

    public static CompoundTag convertPoiCompoundTag(final CompoundTag data, final ServerLevel world) {
        final int dataVersion = getDataVersion(data, DEFAULT_POI_DATA_VERSION);

        return PlatformHooks.get().convertNBT(References.POI_CHUNK, world.getServer().getFixerUpper(), data, dataVersion, getCurrentVersion());
    }

    public static CompoundTag convertEntityChunkCompoundTag(final CompoundTag data, final ServerLevel world) {
        final int dataVersion = getDataVersion(data, DEFAULT_ENTITY_CHUNK_DATA_VERSION);

        return PlatformHooks.get().convertNBT(References.ENTITY_CHUNK, world.getServer().getFixerUpper(), data, dataVersion, getCurrentVersion());
    }

    private ChunkSystemConverters() {}
}
