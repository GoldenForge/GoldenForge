package ca.spottedleaf.moonrise.patches.chunk_system;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

public final class ChunkSystemConverters {

    // See SectionStorage#getVersion
    private static final int DEFAULT_POI_DATA_VERSION = 1945;

    private static final int DEFAULT_ENTITY_CHUNK_DATA_VERSION = -1;

    private static int getCurrentVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static int getDataVersion(final CompoundTag data, final int dfl) {
        return !data.contains(SharedConstants.DATA_VERSION_TAG, Tag.TAG_ANY_NUMERIC)
            ? dfl : data.getInt(SharedConstants.DATA_VERSION_TAG);
    }

    public static CompoundTag convertPoiCompoundTag(final CompoundTag data, final ServerLevel world) {
        final int dataVersion = getDataVersion(data, DEFAULT_POI_DATA_VERSION);

        //TODO: check this
//        // Paper start - dataconverter
//        return ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(
//            ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.POI_CHUNK, data, dataVersion, getCurrentVersion()
//        );
//        // Paper end - dataconverter
        return null;
    }

    public static CompoundTag convertEntityChunkCompoundTag(final CompoundTag data, final ServerLevel world) {
        final int dataVersion = getDataVersion(data, DEFAULT_ENTITY_CHUNK_DATA_VERSION);

//        // Paper start - dataconverter
//        return ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(
//            ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.ENTITY_CHUNK, data, dataVersion, getCurrentVersion()
//        );
//        // Paper end - dataconverter
        return null;
    }

    private ChunkSystemConverters() {}
}
