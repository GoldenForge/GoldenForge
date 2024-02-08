package io.papermc.paper.chunk.system.scheduling;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import com.mojang.logging.LogUtils;
import io.papermc.paper.chunk.system.io.RegionFileIOThread;
import io.papermc.paper.chunk.system.poi.PoiChunk;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.slf4j.Logger;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ChunkLoadTask extends ChunkProgressionTask {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final NewChunkHolder chunkHolder;
    private final ChunkDataLoadTask loadTask;

    private volatile boolean cancelled;
    private NewChunkHolder.GenericDataLoadTaskCallback entityLoadTask;
    private NewChunkHolder.GenericDataLoadTaskCallback poiLoadTask;
    private GenericDataLoadTask.TaskResult<ChunkAccess, Throwable> loadResult;
    private final AtomicInteger taskCountToComplete = new AtomicInteger(3); // one for poi, one for entity, and one for chunk data

    protected ChunkLoadTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX, final int chunkZ,
                            final NewChunkHolder chunkHolder, final PrioritisedExecutor.Priority priority) {
        super(scheduler, world, chunkX, chunkZ);
        this.chunkHolder = chunkHolder;
        this.loadTask = new ChunkDataLoadTask(scheduler, world, chunkX, chunkZ, priority);
        this.loadTask.addCallback((final GenericDataLoadTask.TaskResult<ChunkAccess, Throwable> result) -> {
            ChunkLoadTask.this.loadResult = result; // must be before getAndDecrement
            ChunkLoadTask.this.tryCompleteLoad();
        });
    }

    private void tryCompleteLoad() {
        if (this.taskCountToComplete.decrementAndGet() == 0) {
            final GenericDataLoadTask.TaskResult<ChunkAccess, Throwable> result = this.cancelled ? null : this.loadResult; // only after the getAndDecrement
            ChunkLoadTask.this.complete(result == null ? null : result.left(), result == null ? null : result.right());
        }
    }

    @Override
    public ChunkStatus getTargetStatus() {
        return ChunkStatus.EMPTY;
    }

    private boolean scheduled;

    @Override
    public boolean isScheduled() {
        return this.scheduled;
    }

    @Override
    public void schedule() {
        final NewChunkHolder.GenericDataLoadTaskCallback entityLoadTask;
        final NewChunkHolder.GenericDataLoadTaskCallback poiLoadTask;

        final Consumer<GenericDataLoadTask.TaskResult<?, ?>> scheduleLoadTask = (final GenericDataLoadTask.TaskResult<?, ?> result) -> {
            ChunkLoadTask.this.tryCompleteLoad();
        };

        // NOTE: it is IMPOSSIBLE for getOrLoadEntityData/getOrLoadPoiData to complete synchronously, because
        // they must schedule a task to off main or to on main to complete
        final ReentrantAreaLock.Node schedulingLock = this.scheduler.schedulingLockArea.lock(this.chunkX, this.chunkZ);
        try {
            if (this.scheduled) {
                throw new IllegalStateException("schedule() called twice");
            }
            this.scheduled = true;
            if (this.cancelled) {
                return;
            }
            if (!this.chunkHolder.isEntityChunkNBTLoaded()) {
                entityLoadTask = this.chunkHolder.getOrLoadEntityData((Consumer)scheduleLoadTask);
            } else {
                entityLoadTask = null;
                this.taskCountToComplete.getAndDecrement(); // we know the chunk load is not done here, as it is not scheduled
            }

            if (!this.chunkHolder.isPoiChunkLoaded()) {
                poiLoadTask = this.chunkHolder.getOrLoadPoiData((Consumer)scheduleLoadTask);
            } else {
                poiLoadTask = null;
                this.taskCountToComplete.getAndDecrement(); // we know the chunk load is not done here, as it is not scheduled
            }

            this.entityLoadTask = entityLoadTask;
            this.poiLoadTask = poiLoadTask;
        } finally {
            this.scheduler.schedulingLockArea.unlock(schedulingLock);
        }

        if (entityLoadTask != null) {
            entityLoadTask.schedule();
        }

        if (poiLoadTask != null) {
            poiLoadTask.schedule();
        }

        this.loadTask.schedule(false);
    }

    @Override
    public void cancel() {
        // must be before load task access, so we can synchronise with the writes to the fields
        final boolean scheduled;
        final ReentrantAreaLock.Node schedulingLock = this.scheduler.schedulingLockArea.lock(this.chunkX, this.chunkZ);
        try {
            // must read field here, as it may be written later conucrrently -
            // we need to know if we scheduled _before_ cancellation
            scheduled = this.scheduled;
            this.cancelled = true;
        } finally {
            this.scheduler.schedulingLockArea.unlock(schedulingLock);
        }

        /*
        Note: The entityLoadTask/poiLoadTask do not complete when cancelled,
        so we need to manually try to complete in those cases
        It is also important to note that we set the cancelled field first, just in case
        the chunk load task attempts to complete with a non-null value
        */

        if (scheduled) {
            // since we scheduled, we need to cancel the tasks
            if (this.entityLoadTask != null) {
                if (this.entityLoadTask.cancel()) {
                    this.tryCompleteLoad();
                }
            }
            if (this.poiLoadTask != null) {
                if (this.poiLoadTask.cancel()) {
                    this.tryCompleteLoad();
                }
            }
        } else {
            // since nothing was scheduled, we need to decrement the task count here ourselves

            // for entity load task
            this.tryCompleteLoad();

            // for poi load task
            this.tryCompleteLoad();
        }
        this.loadTask.cancel();
    }

    @Override
    public PrioritisedExecutor.Priority getPriority() {
        return this.loadTask.getPriority();
    }

    @Override
    public void lowerPriority(final PrioritisedExecutor.Priority priority) {
        final EntityDataLoadTask entityLoad = this.chunkHolder.getEntityDataLoadTask();
        if (entityLoad != null) {
            entityLoad.lowerPriority(priority);
        }

        final PoiDataLoadTask poiLoad = this.chunkHolder.getPoiDataLoadTask();

        if (poiLoad != null) {
            poiLoad.lowerPriority(priority);
        }

        this.loadTask.lowerPriority(priority);
    }

    @Override
    public void setPriority(final PrioritisedExecutor.Priority priority) {
        final EntityDataLoadTask entityLoad = this.chunkHolder.getEntityDataLoadTask();
        if (entityLoad != null) {
            entityLoad.setPriority(priority);
        }

        final PoiDataLoadTask poiLoad = this.chunkHolder.getPoiDataLoadTask();

        if (poiLoad != null) {
            poiLoad.setPriority(priority);
        }

        this.loadTask.setPriority(priority);
    }

    @Override
    public void raisePriority(final PrioritisedExecutor.Priority priority) {
        final EntityDataLoadTask entityLoad = this.chunkHolder.getEntityDataLoadTask();
        if (entityLoad != null) {
            entityLoad.raisePriority(priority);
        }

        final PoiDataLoadTask poiLoad = this.chunkHolder.getPoiDataLoadTask();

        if (poiLoad != null) {
            poiLoad.raisePriority(priority);
        }

        this.loadTask.raisePriority(priority);
    }

    protected static abstract class CallbackDataLoadTask<OnMain,FinalCompletion> extends GenericDataLoadTask<OnMain,FinalCompletion> {

        private TaskResult<FinalCompletion, Throwable> result;
        private final MultiThreadedQueue<Consumer<TaskResult<FinalCompletion, Throwable>>> waiters = new MultiThreadedQueue<>();

        protected volatile boolean completed;
        protected static final VarHandle COMPLETED_HANDLE = ConcurrentUtil.getVarHandle(CallbackDataLoadTask.class, "completed", boolean.class);

        protected CallbackDataLoadTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX,
                                       final int chunkZ, final RegionFileIOThread.RegionFileType type,
                                       final PrioritisedExecutor.Priority priority) {
            super(scheduler, world, chunkX, chunkZ, type, priority);
        }

        public void addCallback(final Consumer<TaskResult<FinalCompletion, Throwable>> consumer) {
            if (!this.waiters.add(consumer)) {
                try {
                    consumer.accept(this.result);
                } catch (final Throwable throwable) {
                    this.scheduler.unrecoverableChunkSystemFailure(this.chunkX, this.chunkZ, Map.of(
                        "Consumer", ChunkTaskScheduler.stringIfNull(consumer),
                        "Completed throwable", ChunkTaskScheduler.stringIfNull(this.result.right())
                    ), throwable);
                    if (throwable instanceof ThreadDeath) {
                        throw (ThreadDeath)throwable;
                    }
                }
            }
        }

        @Override
        protected void onComplete(final TaskResult<FinalCompletion, Throwable> result) {
            if ((boolean)COMPLETED_HANDLE.getAndSet((CallbackDataLoadTask)this, (boolean)true)) {
                throw new IllegalStateException("Already completed");
            }
            this.result = result;
            Consumer<TaskResult<FinalCompletion, Throwable>> consumer;
            while ((consumer = this.waiters.pollOrBlockAdds()) != null) {
                try {
                    consumer.accept(result);
                } catch (final Throwable throwable) {
                    this.scheduler.unrecoverableChunkSystemFailure(this.chunkX, this.chunkZ, Map.of(
                        "Consumer", ChunkTaskScheduler.stringIfNull(consumer),
                        "Completed throwable", ChunkTaskScheduler.stringIfNull(result.right())
                    ), throwable);
                    if (throwable instanceof ThreadDeath) {
                        throw (ThreadDeath)throwable;
                    }
                    return;
                }
            }
        }
    }

    public static final class ChunkDataLoadTask extends CallbackDataLoadTask<ChunkAccess, ChunkAccess> {
        protected ChunkDataLoadTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX,
                                    final int chunkZ, final PrioritisedExecutor.Priority priority) {
            super(scheduler, world, chunkX, chunkZ, RegionFileIOThread.RegionFileType.CHUNK_DATA, priority);
        }

        @Override
        protected boolean hasOffMain() {
            return true;
        }

        @Override
        protected boolean hasOnMain() {
            return false;
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOffMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            return this.scheduler.loadExecutor.createTask(run, priority);
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOnMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected TaskResult<ChunkAccess, Throwable> completeOnMainOffMain(final ChunkAccess data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }

        private ProtoChunk getEmptyChunk() {
            return new ProtoChunk(
                new ChunkPos(this.chunkX, this.chunkZ), UpgradeData.EMPTY, this.world,
                this.world.registryAccess().registryOrThrow(Registries.BIOME), (BlendingData)null
            );
        }

        @Override
        protected TaskResult<ChunkAccess, Throwable> runOffMain(final CompoundTag data, final Throwable throwable) {
            if (throwable != null) {
                LOGGER.error("Failed to load chunk data for task: " + this.toString() + ", chunk data will be lost", throwable);
                return new TaskResult<>(this.getEmptyChunk(), null);
            }

            if (data == null) {
                return new TaskResult<>(this.getEmptyChunk(), null);
            }

            // need to convert data, and then deserialize it

            try {
                final ChunkPos chunkPos = new ChunkPos(this.chunkX, this.chunkZ);
                final ChunkMap chunkMap = this.world.getChunkSource().chunkMap;
                // run converters
                // note: upgradeChunkTag copies the data already
                final CompoundTag converted = chunkMap.upgradeChunkTag(
                    this.world.getTypeKey(), chunkMap.overworldDataStorage, data, chunkMap.generator.getTypeNameForDataFixer(),
                    chunkPos, this.world
                );
                // deserialize
                final ChunkSerializer.InProgressChunkHolder chunkHolder = ChunkSerializer.readInProgressChunkHolder(
                    this.world, chunkMap.getPoiManager(), chunkPos, converted
                );

                return new TaskResult<>(chunkHolder.protoChunk, null);
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr2) {
                LOGGER.error("Failed to parse chunk data for task: " + this.toString() + ", chunk data will be lost", thr2);
                return new TaskResult<>(this.getEmptyChunk(), null);
            }
        }

        @Override
        protected TaskResult<ChunkAccess, Throwable> runOnMain(final ChunkAccess data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class PoiDataLoadTask extends CallbackDataLoadTask<PoiChunk, PoiChunk> {
        public PoiDataLoadTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX,
                               final int chunkZ, final PrioritisedExecutor.Priority priority) {
            super(scheduler, world, chunkX, chunkZ, RegionFileIOThread.RegionFileType.POI_DATA, priority);
        }

        @Override
        protected boolean hasOffMain() {
            return true;
        }

        @Override
        protected boolean hasOnMain() {
            return false;
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOffMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            return this.scheduler.loadExecutor.createTask(run, priority);
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOnMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected TaskResult<PoiChunk, Throwable> completeOnMainOffMain(final PoiChunk data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected TaskResult<PoiChunk, Throwable> runOffMain(CompoundTag data, final Throwable throwable) {
            if (throwable != null) {
                LOGGER.error("Failed to load poi data for task: " + this.toString() + ", poi data will be lost", throwable);
                return new TaskResult<>(PoiChunk.empty(this.world, this.chunkX, this.chunkZ), null);
            }

            if (data == null || data.isEmpty()) {
                // nothing to do
                return new TaskResult<>(PoiChunk.empty(this.world, this.chunkX, this.chunkZ), null);
            }

            try {
                data = data.copy(); // coming from the I/O thread, so we need to copy
                // run converters
                final int dataVersion = !data.contains(SharedConstants.DATA_VERSION_TAG, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? 1945 : data.getInt(SharedConstants.DATA_VERSION_TAG);
                final CompoundTag converted = MCDataConverter.convertTag(
                    MCTypeRegistry.POI_CHUNK, data, dataVersion, SharedConstants.getCurrentVersion().getDataVersion().getVersion()
                );

                // now we need to parse it
                return new TaskResult<>(PoiChunk.parse(this.world, this.chunkX, this.chunkZ, converted), null);
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr2) {
                LOGGER.error("Failed to run parse poi data for task: " + this.toString() + ", poi data will be lost", thr2);
                return new TaskResult<>(PoiChunk.empty(this.world, this.chunkX, this.chunkZ), null);
            }
        }

        @Override
        protected TaskResult<PoiChunk, Throwable> runOnMain(final PoiChunk data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }
    }

    public static final class EntityDataLoadTask extends CallbackDataLoadTask<CompoundTag, CompoundTag> {

        public EntityDataLoadTask(final ChunkTaskScheduler scheduler, final ServerLevel world, final int chunkX,
                                  final int chunkZ, final PrioritisedExecutor.Priority priority) {
            super(scheduler, world, chunkX, chunkZ, RegionFileIOThread.RegionFileType.ENTITY_DATA, priority);
        }

        @Override
        protected boolean hasOffMain() {
            return true;
        }

        @Override
        protected boolean hasOnMain() {
            return false;
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOffMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            return this.scheduler.loadExecutor.createTask(run, priority);
        }

        @Override
        protected PrioritisedExecutor.PrioritisedTask createOnMain(final Runnable run, final PrioritisedExecutor.Priority priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected TaskResult<CompoundTag, Throwable> completeOnMainOffMain(final CompoundTag data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected TaskResult<CompoundTag, Throwable> runOffMain(final CompoundTag data, final Throwable throwable) {
            if (throwable != null) {
                LOGGER.error("Failed to load entity data for task: " + this.toString() + ", entity data will be lost", throwable);
                return new TaskResult<>(null, null);
            }

            if (data == null || data.isEmpty()) {
                // nothing to do
                return new TaskResult<>(null, null);
            }

            try {
                // note: data comes from the I/O thread, so we need to copy it
                return new TaskResult<>(EntityStorage.upgradeChunkTag(data.copy()), null);
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr2) {
                LOGGER.error("Failed to run converters for entity data for task: " + this.toString() + ", entity data will be lost", thr2);
                return new TaskResult<>(null, thr2);
            }
        }

        @Override
        protected TaskResult<CompoundTag, Throwable> runOnMain(final CompoundTag data, final Throwable throwable) {
            throw new UnsupportedOperationException();
        }
    }
}
