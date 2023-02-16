package com.destroystokyo.paper.io.chunk;

import com.destroystokyo.paper.io.IOUtil;
import com.destroystokyo.paper.io.PaperFileIOThread;
import com.destroystokyo.paper.io.PrioritizedTaskQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkSaveTask extends ChunkTask {

    public final ChunkSerializer.AsyncSaveData asyncSaveData;
    public final ChunkAccess chunk;
    public final CompletableFuture<CompoundTag> onComplete = new CompletableFuture<>();

    private final AtomicInteger attemptedPriority;

    public ChunkSaveTask(final ServerLevel world, final int chunkX, final int chunkZ, final int priority,
                         final ChunkTaskManager taskManager, final ChunkSerializer.AsyncSaveData asyncSaveData,
                         final ChunkAccess chunk) {
        super(world, chunkX, chunkZ, priority, taskManager);
        this.chunk = chunk;
        this.asyncSaveData = asyncSaveData;
        this.attemptedPriority = new AtomicInteger(priority);
    }

    @Override
    public void run() {
        // can be executed asynchronously or synchronously
        final CompoundTag compound;

        try {
            //compound = ChunkSerializer.write(this.world, this.chunk, this.asyncSaveData);
            compound = ChunkSerializer.write(this.world, this.chunk);
        } catch (final Throwable ex) {
            // has a plugin modified something it should not have and made us CME?
            PaperFileIOThread.LOGGER.error("Failed to serialize unloading chunk data for task: " + this.toString() + ", falling back to a synchronous execution", ex);

            // Note: We add to the server thread queue here since this is what the server will drain tasks from
            // when waiting for chunks
            ChunkTaskManager.queueChunkWaitTask(() -> {
                    CompoundTag data = PaperFileIOThread.FAILURE_VALUE;

                    try {
                        data = ChunkSerializer.write(this.world, this.chunk);
                        //data = ChunkSerializer.write(this.world, this.chunk, this.asyncSaveData);
                        PaperFileIOThread.LOGGER.info("Successfully serialized chunk data for task: " + this.toString() + " synchronously");
                    } catch (final Throwable ex1) {
                        PaperFileIOThread.LOGGER.error("Failed to synchronously serialize unloading chunk data for task: " + this.toString() + "! Chunk data will be lost", ex1);
                    }

                    ChunkSaveTask.this.complete(data);

            });

            return; // the main thread will now complete the data
        }

        this.complete(compound);
    }

    @Override
    public boolean raisePriority(final int priority) {
        if (!PrioritizedTaskQueue.validPriority(priority)) {
            throw new IllegalStateException("Invalid priority: " + priority);
        }

        // we know priority is valid here
        for (int curr = this.attemptedPriority.get();;) {
            if (curr <= priority) {
                break; // curr is higher/same priority
            }
            if (this.attemptedPriority.compareAndSet(curr, priority)) {
                break;
            }
            curr = this.attemptedPriority.get();
        }

        return super.raisePriority(priority);
    }

    @Override
    public boolean updatePriority(final int priority) {
        if (!PrioritizedTaskQueue.validPriority(priority)) {
            throw new IllegalStateException("Invalid priority: " + priority);
        }
        this.attemptedPriority.set(priority);
        return super.updatePriority(priority);
    }

    private void complete(final CompoundTag compound) {
        try {
            this.onComplete.complete(compound);
        } catch (final Throwable thr) {
            PaperFileIOThread.LOGGER.error("Failed to complete chunk data for task: " + this.toString(), thr);
        }
        if (compound != PaperFileIOThread.FAILURE_VALUE) {
            PaperFileIOThread.Holder.INSTANCE.scheduleSave(this.world, this.chunkX, this.chunkZ, null, compound, this.attemptedPriority.get());
        }
        this.taskManager.chunkSaveTasks.compute(Long.valueOf(IOUtil.getCoordinateKey(this.chunkX, this.chunkZ)), (final Long keyInMap, final ChunkSaveTask valueInMap) -> {
            if (valueInMap != ChunkSaveTask.this) {
                throw new IllegalStateException("Expected this task to be scheduled, but another was! Other: " + valueInMap + ", this: " + ChunkSaveTask.this);
            }
            return null;
        });
    }
}
