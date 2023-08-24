package me.wesley1808.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.NotNull;

public class ChunkManager {

    @NotNull
    public static Holder<Biome> getRoughBiome(Level level, BlockPos pos) {
        ChunkAccess chunk = ((ServerLevel) level).chunkSource.getChunkNow(pos.getX(), pos.getZ());
        int x = pos.getX() >> 2;
        int y = pos.getY() >> 2;
        int z = pos.getZ() >> 2;

        return chunk != null ? chunk.getNoiseBiome(x, y, z) : level.getUncachedNoiseBiome(x, y, z);
    }
}
