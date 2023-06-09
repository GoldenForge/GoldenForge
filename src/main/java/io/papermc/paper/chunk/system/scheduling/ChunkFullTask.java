package io.papermc.paper.chunk.system.scheduling;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import com.mojang.logging.LogUtils;
import io.papermc.paper.chunk.system.poi.PoiChunk;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.*;
import org.slf4j.Logger;

import java.lang.invoke.VarHandle;

public final class ChunkFullTask extends ChunkProgressionTask implements Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected final NewChunkHolder chunkHolder;
    protected final ChunkAccess fromChunk;
    protected final PrioritisedExecutor.PrioritisedTask convertToFullTask;

    public static final io.papermc.paper.util.IntervalledCounter chunkLoads = new io.papermc.paper.util.IntervalledCounter(java.util.concurrent.TimeUnit.SECONDS.toNanos(15L));
    public static final io.papermc.paper.util.IntervalledCounter chunkGenerates = new io.papermc.paper.util.IntervalledCounter(java.util.concurrent.TimeUnit.SECONDS.toNanos(15L));


    public ChunkFullTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX, final int chunkZ,
                         final NewChunkHolder chunkHolder, final ChunkAccess fromChunk, final PrioritisedExecutor.Priority priority) {
        super(scheduler, world, chunkX, chunkZ);
        this.chunkHolder = chunkHolder;
        this.fromChunk = fromChunk;
        this.convertToFullTask = scheduler.createChunkTask(chunkX, chunkZ, this, priority);
    }

    @Override
    public ChunkStatus getTargetStatus() {
        return ChunkStatus.FULL;
    }

    public static double genRate(final long time) {
        synchronized (chunkGenerates) {
            chunkGenerates.updateCurrentTime(time);
            return chunkGenerates.getRate();
        }
    }

    public static double loadRate(final long time) {
        synchronized (chunkLoads) {
            chunkLoads.updateCurrentTime(time);
            return chunkLoads.getRate();
        }
    }

    @Override
    public void run() {
        // See Vanilla protoChunkToFullChunk for what this function should be doing
        final LevelChunk chunk;
        try {
            // moved from the load from nbt stage into here
            final PoiChunk poiChunk = this.chunkHolder.getPoiChunk();
            if (poiChunk == null) {
                LOGGER.error("Expected poi chunk to be loaded with chunk for task " + this.toString());
            } else {
                poiChunk.load();
                this.world.getPoiManager().checkConsistency(this.fromChunk);
            }


            final long time = System.nanoTime();
            if (this.fromChunk instanceof ImposterProtoChunk wrappedFull) {
                synchronized (chunkLoads) {
                    chunkLoads.updateAndAdd(1L, time);
                }
            } else {
                synchronized (chunkGenerates) {
                    chunkGenerates.updateAndAdd(1L, time);
                }
            }

            if (this.fromChunk instanceof ImposterProtoChunk wrappedFull) {
                chunk = wrappedFull.getWrapped();
            } else {
                final ServerLevel world = this.world;
                final ProtoChunk protoChunk = (ProtoChunk)this.fromChunk;
                chunk = new LevelChunk(this.world, protoChunk, (final LevelChunk unused) -> {
                    ChunkMap.postLoadProtoChunk(world, protoChunk.getEntities());
                });
            }

            chunk.setChunkHolder(this.scheduler.chunkHolderManager.getChunkHolder(this.chunkX, this.chunkZ)); // replaces setFullStatus
            chunk.runPostLoad();
            // Unlike Vanilla, we load the entity chunk here, as we load the NBT in empty status (unlike Vanilla)
            // This brings entity addition back in line with older versions of the game
            // Since we load the NBT in the empty status, this will never block for I/O
            this.world.chunkTaskScheduler.chunkHolderManager.getOrCreateEntityChunk(this.chunkX, this.chunkZ, false);

            // we don't need the entitiesInLevel trash, this system doesn't double run callbacks
            chunk.setLoaded(true);
            chunk.registerAllBlockEntitiesAfterLevelLoad();
            chunk.registerTickContainerInLevel(this.world);
        } catch (final Throwable throwable) {
            this.complete(null, throwable);

            if (throwable instanceof ThreadDeath) {
                throw (ThreadDeath)throwable;
            }
            return;
        }
        this.complete(chunk, null);
    }

    protected volatile boolean scheduled;
    protected static final VarHandle SCHEDULED_HANDLE = ConcurrentUtil.getVarHandle(ChunkFullTask.class, "scheduled", boolean.class);

    @Override
    public boolean isScheduled() {
        return this.scheduled;
    }

    @Override
    public void schedule() {
        if ((boolean)SCHEDULED_HANDLE.getAndSet((ChunkFullTask)this, true)) {
            throw new IllegalStateException("Cannot double call schedule()");
        }
        this.convertToFullTask.queue();
    }

    @Override
    public void cancel() {
        if (this.convertToFullTask.cancel()) {
            this.complete(null, null);
        }
    }

    @Override
    public PrioritisedExecutor.Priority getPriority() {
        return this.convertToFullTask.getPriority();
    }

    @Override
    public void lowerPriority(final PrioritisedExecutor.Priority priority) {
        if (!PrioritisedExecutor.Priority.isValidPriority(priority)) {
            throw new IllegalArgumentException("Invalid priority " + priority);
        }
        this.convertToFullTask.lowerPriority(priority);
    }

    @Override
    public void setPriority(final PrioritisedExecutor.Priority priority) {
        if (!PrioritisedExecutor.Priority.isValidPriority(priority)) {
            throw new IllegalArgumentException("Invalid priority " + priority);
        }
        this.convertToFullTask.setPriority(priority);
    }

    @Override
    public void raisePriority(final PrioritisedExecutor.Priority priority) {
        if (!PrioritisedExecutor.Priority.isValidPriority(priority)) {
            throw new IllegalArgumentException("Invalid priority " + priority);
        }
        this.convertToFullTask.raisePriority(priority);
    }
}
