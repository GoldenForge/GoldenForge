package ca.spottedleaf.moonrise.common.util;

import ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.function.Consumer;

public abstract class BaseChunkSystemHooks implements ChunkSystemHooks {

    @Override
    public void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run) {
        scheduleChunkTask(level, chunkX, chunkZ, run, Priority.NORMAL);
    }

    @Override
    public void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run, final Priority priority) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkTask(chunkX, chunkZ, run, priority);
    }

    @Override
    public void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final boolean gen,
                                  final ChunkStatus toStatus, final boolean addTicket, final Priority priority,
                                  final Consumer<ChunkAccess> onComplete) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, gen, toStatus, addTicket, priority, onComplete);
    }

    @Override
    public void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                  final boolean addTicket, final Priority priority, final Consumer<ChunkAccess> onComplete) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
    }

    @Override
    public void scheduleTickingState(final ServerLevel level, final int chunkX, final int chunkZ,
                                     final FullChunkStatus toStatus, final boolean addTicket,
                                     final Priority priority, final Consumer<LevelChunk> onComplete) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleTickingState(chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
    }

    @Override
    public List<ChunkHolder> getVisibleChunkHolders(final ServerLevel level) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.getOldChunkHolders();
    }

    @Override
    public List<ChunkHolder> getUpdatingChunkHolders(final ServerLevel level) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.getOldChunkHolders();
    }

    @Override
    public int getVisibleChunkHolderCount(final ServerLevel level) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.size();
    }

    @Override
    public int getUpdatingChunkHolderCount(final ServerLevel level) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.size();
    }

    @Override
    public boolean hasAnyChunkHolders(final ServerLevel level) {
        return getUpdatingChunkHolderCount(level) != 0;
    }

    @Override
    public void onChunkHolderCreate(final ServerLevel level, final ChunkHolder holder) {

    }

    @Override
    public void onChunkHolderDelete(final ServerLevel level, final ChunkHolder holder) {
        // Update progress listener for LevelLoadingScreen
        final net.minecraft.server.level.progress.ChunkProgressListener progressListener = level.getChunkSource().chunkMap.progressListener;
        if (progressListener != null) {
            this.scheduleChunkTask(level, holder.getPos().x, holder.getPos().z, () -> {
                progressListener.onStatusChange(holder.getPos(), null);
            });
        }
    }

    @Override
    public void onChunkPreBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache)((ServerLevel)chunk.getLevel()).getChunkSource())
            .moonrise$setFullChunk(chunk.getPos().x, chunk.getPos().z, chunk);
    }

    @Override
    public void onChunkBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getLoadedChunks().add(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
    }

    @Override
    public void onChunkNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getLoadedChunks().remove(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
    }

    @Override
    public void onChunkPostNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache)((ServerLevel)chunk.getLevel()).getChunkSource())
            .moonrise$setFullChunk(chunk.getPos().x, chunk.getPos().z, null);
    }

    @Override
    public void onChunkTicking(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getTickingChunks().add(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
        if (!((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$isPostProcessingDone()) {
            chunk.postProcessGeneration();
        }
        ((ServerLevel)chunk.getLevel()).startTickingChunk(chunk);
        ((ServerLevel)chunk.getLevel()).getChunkSource().chunkMap.tickingGenerated.incrementAndGet();
        ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickServerLevel)(ServerLevel)chunk.getLevel()).moonrise$markChunkForPlayerTicking(chunk); // Moonrise - chunk tick iteration
    }

    @Override
    public void onChunkNotTicking(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getTickingChunks().remove(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
        ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickServerLevel)(ServerLevel)chunk.getLevel()).moonrise$removeChunkForPlayerTicking(chunk); // Moonrise - chunk tick iteration
    }

    @Override
    public void onChunkEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getEntityTickingChunks().add(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
    }

    @Override
    public void onChunkNotEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)((ServerLevel)chunk.getLevel())).moonrise$getEntityTickingChunks().remove(
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)chunk).moonrise$getChunkAndHolder()
        );
    }

    @Override
    public ChunkHolder getUnloadingChunkHolder(final ServerLevel level, final int chunkX, final int chunkZ) {
        return null;
    }

    @Override
    public int getSendViewDistance(final ServerPlayer player) {
        return ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.getAPISendViewDistance(player);
    }

    @Override
    public int getViewDistance(final ServerPlayer player) {
        return ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.getAPIViewDistance(player);
    }

    @Override
    public int getTickViewDistance(final ServerPlayer player) {
        return ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.getAPITickViewDistance(player);
    }

    @Override
    public void addPlayerToDistanceMaps(final ServerLevel world, final ServerPlayer player) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().addPlayer(player);
    }

    @Override
    public void removePlayerFromDistanceMaps(final ServerLevel world, final ServerPlayer player) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().removePlayer(player);
    }

    @Override
    public void updateMaps(final ServerLevel world, final ServerPlayer player) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().updatePlayer(player);
    }
}