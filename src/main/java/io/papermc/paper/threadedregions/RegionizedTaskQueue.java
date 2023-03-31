package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.map.SWMRLong2ObjectHashTable;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import io.papermc.paper.chunk.system.scheduling.ChunkHolderManager;
import io.papermc.paper.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;

import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class RegionizedTaskQueue {

    private static final TicketType<Unit> TASK_QUEUE_TICKET = TicketType.create("task_queue_ticket", (a, b) -> 0);

    public PrioritisedExecutor.PrioritisedTask createChunkTask(final ServerLevel world, final int chunkX, final int chunkZ,
                                                               final Runnable run) {
        return new PrioritisedQueue.ChunkBasedPriorityTask(world.taskQueueRegionData, chunkX, chunkZ, true, run, PrioritisedExecutor.Priority.NORMAL);
    }

    public PrioritisedExecutor.PrioritisedTask createChunkTask(final ServerLevel world, final int chunkX, final int chunkZ,
                                                               final Runnable run, final PrioritisedExecutor.Priority priority) {
        return new PrioritisedQueue.ChunkBasedPriorityTask(world.taskQueueRegionData, chunkX, chunkZ, true, run, priority);
    }

    public PrioritisedExecutor.PrioritisedTask createTickTaskQueue(final ServerLevel world, final int chunkX, final int chunkZ,
                                                                   final Runnable run) {
        return new PrioritisedQueue.ChunkBasedPriorityTask(world.taskQueueRegionData, chunkX, chunkZ, false, run, PrioritisedExecutor.Priority.NORMAL);
    }

    public PrioritisedExecutor.PrioritisedTask createTickTaskQueue(final ServerLevel world, final int chunkX, final int chunkZ,
                                                                   final Runnable run, final PrioritisedExecutor.Priority priority) {
        return new PrioritisedQueue.ChunkBasedPriorityTask(world.taskQueueRegionData, chunkX, chunkZ, false, run, priority);
    }

    public PrioritisedExecutor.PrioritisedTask queueChunkTask(final ServerLevel world, final int chunkX, final int chunkZ,
                                                              final Runnable run) {
        return this.queueChunkTask(world, chunkX, chunkZ, run, PrioritisedExecutor.Priority.NORMAL);
    }

    public PrioritisedExecutor.PrioritisedTask queueChunkTask(final ServerLevel world, final int chunkX, final int chunkZ,
                                                              final Runnable run, final PrioritisedExecutor.Priority priority) {
        final PrioritisedExecutor.PrioritisedTask ret = this.createChunkTask(world, chunkX, chunkZ, run, priority);
        ret.queue();
        return ret;
    }

    public PrioritisedExecutor.PrioritisedTask queueTickTaskQueue(final ServerLevel world, final int chunkX, final int chunkZ,
                                                                  final Runnable run) {
        return this.queueTickTaskQueue(world, chunkX, chunkZ, run, PrioritisedExecutor.Priority.NORMAL);
    }

    public PrioritisedExecutor.PrioritisedTask queueTickTaskQueue(final ServerLevel world, final int chunkX, final int chunkZ,
                                                                  final Runnable run, final PrioritisedExecutor.Priority priority) {
        final PrioritisedExecutor.PrioritisedTask ret = this.createTickTaskQueue(world, chunkX, chunkZ, run, priority);
        ret.queue();
        return ret;
    }

    public static final class WorldRegionTaskData {
        private final ServerLevel world;
        private final MultiThreadedQueue<Runnable> globalChunkTask = new MultiThreadedQueue<>();
        private final SWMRLong2ObjectHashTable<AtomicLong> referenceCounters = new SWMRLong2ObjectHashTable<>();

        public WorldRegionTaskData(final ServerLevel world) {
            this.world = world;
        }

        private boolean executeGlobalChunkTask() {
            final Runnable run = this.globalChunkTask.poll();
            if (run != null) {
                run.run();
                return true;
            }
            return false;
        }

        public void drainGlobalChunkTasks() {
            while (this.executeGlobalChunkTask());
        }

        public void pushGlobalChunkTask(final Runnable run) {
            this.globalChunkTask.add(run);
        }

        private PrioritisedQueue getQueue(final boolean synchronise, final int chunkX, final int chunkZ, final boolean isChunkTask) {
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = this.world.regioniser;
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
                = synchronise ? regioniser.getRegionAtSynchronised(chunkX, chunkZ) : regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
            if (region == null) {
                return null;
            }
            final RegionTaskQueueData taskQueueData = region.getData().getTaskQueueData();
            return (isChunkTask ? taskQueueData.chunkQueue : taskQueueData.tickTaskQueue);
        }

        private void removeTicket(final long coord) {
            this.world.chunkTaskScheduler.chunkHolderManager.removeTicketAtLevel(
                TASK_QUEUE_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, Unit.INSTANCE
            );
        }

        private void addTicket(final long coord) {
            this.world.chunkTaskScheduler.chunkHolderManager.addTicketAtLevel(
                TASK_QUEUE_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, Unit.INSTANCE
            );
        }

        private void decrementReference(final AtomicLong reference, final long coord) {
            final long val = reference.decrementAndGet();
            if (val == 0L) {
                final ReentrantLock ticketLock = this.world.chunkTaskScheduler.chunkHolderManager.ticketLock;
                ticketLock.lock();
                try {
                    if (this.referenceCounters.remove(coord, reference)) {
                        WorldRegionTaskData.this.removeTicket(coord);
                    } // else: race condition, something replaced our reference - not our issue anymore
                } finally {
                    ticketLock.unlock();
                }
            } else if (val < 0L) {
                throw new IllegalStateException("Reference count < 0: " + val);
            }
        }

        private AtomicLong incrementReference(final long coord) {
            final AtomicLong ret = this.referenceCounters.get(coord);
            if (ret != null) {
                // try to fast acquire counter
                int failures = 0;
                for (long curr = ret.get();;) {
                    if (curr == 0L) {
                        // failed to fast acquire as reference expired
                        break;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr == (curr = ret.compareAndExchange(curr, curr + 1L))) {
                        return ret;
                    }

                    ++failures;
                }
            }

            // slow acquire
            final ReentrantLock ticketLock = this.world.chunkTaskScheduler.chunkHolderManager.ticketLock;
            ticketLock.lock();
            try {
                final AtomicLong replace = new AtomicLong(1L);
                final AtomicLong valueInMap = this.referenceCounters.putIfAbsent(coord, replace);
                if (valueInMap == null) {
                    // replaced, we should usually be here
                    this.addTicket(coord);
                    return replace;
                } // else: need to attempt to acquire the reference

                int failures = 0;
                for (long curr = valueInMap.get();;) {
                    if (curr == 0L) {
                        // don't need to add ticket here, since ticket is only removed during the lock
                        // we just need to replace the value in the map so that the thread removing fails and doesn't
                        // remove the ticket (see decrementReference)
                        this.referenceCounters.put(coord, replace);
                        return replace;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr == (curr = valueInMap.compareAndExchange(curr, curr + 1L))) {
                        // acquired
                        return valueInMap;
                    }

                    ++failures;
                }
            } finally {
                ticketLock.unlock();
            }
        }
    }

    public static final class RegionTaskQueueData {
        private final PrioritisedQueue tickTaskQueue = new PrioritisedQueue();
        private final PrioritisedQueue chunkQueue = new PrioritisedQueue();
        private final WorldRegionTaskData worldRegionTaskData;

        public RegionTaskQueueData(final WorldRegionTaskData worldRegionTaskData) {
            this.worldRegionTaskData = worldRegionTaskData;
        }

        void mergeInto(final RegionTaskQueueData into) {
            this.tickTaskQueue.mergeInto(into.tickTaskQueue);
            this.chunkQueue.mergeInto(into.chunkQueue);
        }

        public boolean executeTickTask() {
            return this.tickTaskQueue.executeTask();
        }

        public boolean executeChunkTask() {
            return this.worldRegionTaskData.executeGlobalChunkTask() || this.chunkQueue.executeTask();
        }

        void split(final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser,
                   final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> into) {
            this.tickTaskQueue.split(
                false, regioniser, into
            );
            this.chunkQueue.split(
                true, regioniser, into
            );
        }

        public void drainTasks() {
            // first, update chunk loader
            final ServerLevel world = this.worldRegionTaskData.world;
            if (world.playerChunkLoader.tickMidTick()) {
                // only process ticket updates if the player chunk loader did anything
                world.chunkTaskScheduler.chunkHolderManager.processTicketUpdates();
            }

            final PrioritisedQueue tickTaskQueue = this.tickTaskQueue;
            final PrioritisedQueue chunkTaskQueue = this.chunkQueue;

            int allowedTickTasks = tickTaskQueue.getScheduledTasks();
            int allowedChunkTasks = chunkTaskQueue.getScheduledTasks();

            boolean executeTickTasks = allowedTickTasks > 0;
            boolean executeChunkTasks = allowedChunkTasks > 0;
            boolean executeGlobalTasks = true;

            do {
                executeTickTasks = executeTickTasks && allowedTickTasks-- > 0 && tickTaskQueue.executeTask();
                executeChunkTasks = executeChunkTasks && allowedChunkTasks-- > 0 && chunkTaskQueue.executeTask();
                executeGlobalTasks = executeGlobalTasks && this.worldRegionTaskData.executeGlobalChunkTask();
            } while (executeTickTasks | executeChunkTasks | executeGlobalTasks);

            if (allowedChunkTasks > 0) {
                // if we executed chunk tasks, we should try to process ticket updates for full status changes
                world.chunkTaskScheduler.chunkHolderManager.processTicketUpdates();
            }
        }

        public boolean hasTasks() {
            return !this.tickTaskQueue.isEmpty() || !this.chunkQueue.isEmpty();
        }
    }

    static final class PrioritisedQueue {
        private final ArrayDeque<ChunkBasedPriorityTask>[] queues = new ArrayDeque[PrioritisedExecutor.Priority.TOTAL_SCHEDULABLE_PRIORITIES]; {
            for (int i = 0; i < PrioritisedExecutor.Priority.TOTAL_SCHEDULABLE_PRIORITIES; ++i) {
                this.queues[i] = new ArrayDeque<>();
            }
        }
        private boolean isDestroyed;

        public int getScheduledTasks() {
            synchronized (this) {
                int ret = 0;

                for (final ArrayDeque<ChunkBasedPriorityTask> queue : this.queues) {
                    ret += queue.size();
                }

                return ret;
            }
        }

        public boolean isEmpty() {
            final ArrayDeque<ChunkBasedPriorityTask>[] queues = this.queues;
            final int max = PrioritisedExecutor.Priority.IDLE.priority;
            synchronized (this) {
                for (int i = 0; i <= max; ++i) {
                    if (!queues[i].isEmpty()) {
                        return false;
                    }
                }
                return true;
            }
        }

        public void mergeInto(final PrioritisedQueue target) {
            synchronized (this) {
                this.isDestroyed = true;
                synchronized (target) {
                    mergeInto(target, this.queues);
                }
            }
        }

        private static void mergeInto(final PrioritisedQueue target, final ArrayDeque<ChunkBasedPriorityTask>[] thisQueues) {
            final ArrayDeque<ChunkBasedPriorityTask>[] otherQueues = target.queues;
            for (int i = 0; i < thisQueues.length; ++i) {
                final ArrayDeque<ChunkBasedPriorityTask> fromQ = thisQueues[i];
                final ArrayDeque<ChunkBasedPriorityTask> intoQ = otherQueues[i];

                // it is possible for another thread to queue tasks into the target queue before we do
                // since only the ticking region can poll, we don't have to worry about it when they are being queued -
                // but when we are merging, we need to ensure order is maintained (notwithstanding priority changes)
                // we can ensure order is maintained by adding all of the tasks from the fromQ into the intoQ at the
                // front of the queue, but we need to use descending iterator to ensure we do not reverse
                // the order of elements from fromQ
                for (final Iterator<ChunkBasedPriorityTask> iterator = fromQ.descendingIterator(); iterator.hasNext();) {
                    intoQ.addFirst(iterator.next());
                }
            }
        }

        // into is a map of section coordinate to region
        public void split(final boolean isChunkData,
                          final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser,
                          final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> into) {
            final Reference2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, ArrayDeque<ChunkBasedPriorityTask>[]>
                split = new Reference2ReferenceOpenHashMap<>();
            final int shift = regioniser.sectionChunkShift;
            synchronized (this) {
                this.isDestroyed = true;
                // like mergeTarget, we need to be careful about insertion order so we can maintain order when splitting

                // first, build the targets
                final ArrayDeque<ChunkBasedPriorityTask>[] thisQueues = this.queues;
                for (int i = 0; i < thisQueues.length; ++i) {
                    final ArrayDeque<ChunkBasedPriorityTask> fromQ = thisQueues[i];

                    for (final ChunkBasedPriorityTask task : fromQ) {
                        final int sectionX = task.chunkX >> shift;
                        final int sectionZ = task.chunkZ >> shift;
                        final long sectionKey = CoordinateUtils.getChunkKey(sectionX, sectionZ);
                        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>
                            region = into.get(sectionKey);
                        if (region == null) {
                            throw new IllegalStateException();
                        }

                        split.computeIfAbsent(region, (keyInMap) -> {
                            final ArrayDeque<ChunkBasedPriorityTask>[] ret = new ArrayDeque[PrioritisedExecutor.Priority.TOTAL_SCHEDULABLE_PRIORITIES];

                            for (int k = 0; k < ret.length; ++k) {
                                ret[k] = new ArrayDeque<>();
                            }

                            return ret;
                        })[i].add(task);
                    }
                }

                // merge the targets into their queues
                for (final Iterator<Reference2ReferenceMap.Entry<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, ArrayDeque<ChunkBasedPriorityTask>[]>>
                     iterator = split.reference2ReferenceEntrySet().fastIterator();
                     iterator.hasNext();) {
                    final Reference2ReferenceMap.Entry<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>, ArrayDeque<ChunkBasedPriorityTask>[]>
                        entry = iterator.next();
                    final RegionTaskQueueData taskQueueData = entry.getKey().getData().getTaskQueueData();
                    mergeInto(isChunkData ? taskQueueData.chunkQueue : taskQueueData.tickTaskQueue, entry.getValue());
                }
            }
        }

        /**
         * returns null if the task cannot be scheduled, returns false if this task queue is dead, and returns true
         * if the task was added
         */
        private Boolean tryPush(final ChunkBasedPriorityTask task) {
            final ArrayDeque<ChunkBasedPriorityTask>[] queues = this.queues;
            synchronized (this) {
                final PrioritisedExecutor.Priority priority = task.getPriority();
                if (priority == PrioritisedExecutor.Priority.COMPLETING) {
                    return null;
                }
                if (this.isDestroyed) {
                    return Boolean.FALSE;
                }
                queues[priority.priority].addLast(task);
                return Boolean.TRUE;
            }
        }

        private boolean executeTask() {
            final ArrayDeque<ChunkBasedPriorityTask>[] queues = this.queues;
            final int max = PrioritisedExecutor.Priority.IDLE.priority;
            ChunkBasedPriorityTask task = null;
            AtomicLong referenceCounter = null;
            synchronized (this) {
                if (this.isDestroyed) {
                    throw new IllegalStateException("Attempting to poll from dead queue");
                }

                search_loop:
                for (int i = 0; i <= max; ++i) {
                    final ArrayDeque<ChunkBasedPriorityTask> queue = queues[i];
                    while ((task = queue.pollFirst()) != null) {
                        if ((referenceCounter = task.trySetCompleting(i)) != null) {
                            break search_loop;
                        }
                    }
                }
            }

            if (task == null) {
                return false;
            }

            try {
                task.executeInternal();
            } finally {
                task.world.decrementReference(referenceCounter, task.sectionLowerLeftCoord);
            }

            return true;
        }

        private static final class ChunkBasedPriorityTask implements PrioritisedExecutor.PrioritisedTask {

            private static final AtomicLong REFERENCE_COUNTER_NOT_SET = new AtomicLong(-1L);

            private final WorldRegionTaskData world;
            private final int chunkX;
            private final int chunkZ;
            private final long sectionLowerLeftCoord; // chunk coordinate
            private final boolean isChunkTask;

            private volatile AtomicLong referenceCounter;
            private static final VarHandle REFERENCE_COUNTER_HANDLE = ConcurrentUtil.getVarHandle(ChunkBasedPriorityTask.class, "referenceCounter", AtomicLong.class);
            private Runnable run;
            private volatile PrioritisedExecutor.Priority priority;
            private static final VarHandle PRIORITY_HANDLE = ConcurrentUtil.getVarHandle(ChunkBasedPriorityTask.class, "priority", PrioritisedExecutor.Priority.class);

            ChunkBasedPriorityTask(final WorldRegionTaskData world, final int chunkX, final int chunkZ, final boolean isChunkTask,
                                   final Runnable run, final PrioritisedExecutor.Priority priority) {
                this.world = world;
                this.chunkX = chunkX;
                this.chunkZ = chunkZ;
                this.isChunkTask = isChunkTask;
                this.run = run;
                this.setReferenceCounterPlain(REFERENCE_COUNTER_NOT_SET);
                this.setPriorityPlain(priority);

                final int regionShift = world.world.regioniser.sectionChunkShift;
                final int regionMask = (1 << regionShift) - 1;

                this.sectionLowerLeftCoord = CoordinateUtils.getChunkKey(chunkX & ~regionMask, chunkZ & ~regionMask);
            }

            private PrioritisedExecutor.Priority getPriorityVolatile() {
                return (PrioritisedExecutor.Priority)PRIORITY_HANDLE.getVolatile(this);
            }

            private void setPriorityPlain(final PrioritisedExecutor.Priority priority) {
                PRIORITY_HANDLE.set(this, priority);
            }

            private void setPriorityVolatile(final PrioritisedExecutor.Priority priority) {
                PRIORITY_HANDLE.setVolatile(this, priority);
            }

            private PrioritisedExecutor.Priority compareAndExchangePriority(final PrioritisedExecutor.Priority expect, final PrioritisedExecutor.Priority update) {
                return (PrioritisedExecutor.Priority)PRIORITY_HANDLE.compareAndExchange(this, expect, update);
            }

            private void setReferenceCounterPlain(final AtomicLong value) {
                REFERENCE_COUNTER_HANDLE.set(this, value);
            }

            private AtomicLong getReferenceCounterVolatile() {
                return (AtomicLong)REFERENCE_COUNTER_HANDLE.get(this);
            }

            private AtomicLong compareAndExchangeReferenceCounter(final AtomicLong expect, final AtomicLong update) {
                return (AtomicLong)REFERENCE_COUNTER_HANDLE.compareAndExchange(this, expect, update);
            }

            private void executeInternal() {
                try {
                    this.run.run();
                } finally {
                    this.run = null;
                }
            }

            private void cancelInternal() {
                this.run = null;
            }

            private boolean tryComplete(final boolean cancel) {
                int failures = 0;
                for (AtomicLong curr = this.getReferenceCounterVolatile();;) {
                    if (curr == null) {
                        return false;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr != (curr = this.compareAndExchangeReferenceCounter(curr, null))) {
                        ++failures;
                        continue;
                    }

                    // we have the reference count, we win no matter what.
                    this.setPriorityVolatile(PrioritisedExecutor.Priority.COMPLETING);

                    try {
                        if (cancel) {
                            this.cancelInternal();
                        } else {
                            this.executeInternal();
                        }
                    } finally {
                        if (curr != REFERENCE_COUNTER_NOT_SET) {
                            this.world.decrementReference(curr, this.sectionLowerLeftCoord);
                        }
                    }

                    return true;
                }
            }

            @Override
            public boolean queue() {
                if (this.getReferenceCounterVolatile() != REFERENCE_COUNTER_NOT_SET) {
                    return false;
                }

                final AtomicLong referenceCounter = this.world.incrementReference(this.sectionLowerLeftCoord);
                if (this.compareAndExchangeReferenceCounter(REFERENCE_COUNTER_NOT_SET, referenceCounter) != REFERENCE_COUNTER_NOT_SET) {
                    // we don't expect race conditions here, so it is OK if we have to needlessly reference count
                    this.world.decrementReference(referenceCounter, this.sectionLowerLeftCoord);
                    return false;
                }

                boolean synchronise = false;
                for (;;) {
                    // we need to synchronise for repeated operations so that we guarantee that we do not retrieve
                    // the same queue again, as the region lock will be given to us only when the merge/split operation
                    // is done
                    final PrioritisedQueue queue = this.world.getQueue(synchronise, this.chunkX, this.chunkZ, this.isChunkTask);

                    if (queue == null) {
                        if (!synchronise) {
                            // may be incorrectly null when unsynchronised
                            continue;
                        }
                        // may have been cancelled before we got to the queue
                        if (this.getReferenceCounterVolatile() != null) {
                            throw new IllegalStateException("Expected null ref count when queue does not exist");
                        }
                        // the task never could be polled from the queue, so we return false
                        // don't decrement reference count, as we were certainly cancelled by another thread, which
                        // will decrement the reference count
                        return false;
                    }

                    synchronise = true;

                    final Boolean res = queue.tryPush(this);
                    if (res == null) {
                        // we were cancelled
                        // don't decrement reference count, as we were certainly cancelled by another thread, which
                        // will decrement the reference count
                        return false;
                    }

                    if (!res.booleanValue()) {
                        // failed, try again
                        continue;
                    }

                    // successfully queued
                    return true;
                }
            }

            private AtomicLong trySetCompleting(final int minPriority) {
                // first, try to set priority to EXECUTING
                for (PrioritisedExecutor.Priority curr = this.getPriorityVolatile();;) {
                    if (curr.isLowerPriority(minPriority)) {
                        return null;
                    }

                    if (curr == (curr = this.compareAndExchangePriority(curr, PrioritisedExecutor.Priority.COMPLETING))) {
                        break;
                    } // else: continue
                }

                for (AtomicLong curr = this.getReferenceCounterVolatile();;) {
                    if (curr == null) {
                        // something acquired before us
                        return null;
                    }

                    if (curr == REFERENCE_COUNTER_NOT_SET) {
                        throw new IllegalStateException();
                    }

                    if (curr != (curr = this.compareAndExchangeReferenceCounter(curr, null))) {
                        continue;
                    }
                    return curr;
                }
            }

            private void updatePriorityInQueue() {
                boolean synchronise = false;
                for (;;) {
                    final AtomicLong referenceCount = this.getReferenceCounterVolatile();
                    if (referenceCount == REFERENCE_COUNTER_NOT_SET || referenceCount == null) {
                        // cancelled or not queued
                        return;
                    }

                    if (this.getPriorityVolatile() == PrioritisedExecutor.Priority.COMPLETING) {
                        // cancelled
                        return;
                    }

                    // we need to synchronise for repeated operations so that we guarantee that we do not retrieve
                    // the same queue again, as the region lock will be given to us only when the merge/split operation
                    // is done
                    final PrioritisedQueue queue = this.world.getQueue(synchronise, this.chunkX, this.chunkZ, this.isChunkTask);

                    if (queue == null) {
                        if (!synchronise) {
                            // may be incorrectly null when unsynchronised
                            continue;
                        }
                        // must have been removed
                        return;
                    }

                    synchronise = true;

                    final Boolean res = queue.tryPush(this);
                    if (res == null) {
                        // we were cancelled
                        return;
                    }

                    if (!res.booleanValue()) {
                        // failed, try again
                        continue;
                    }

                    // successfully queued
                    return;
                }
            }

            @Override
            public PrioritisedExecutor.Priority getPriority() {
                return this.getPriorityVolatile();
            }

            @Override
            public boolean lowerPriority(final PrioritisedExecutor.Priority priority) {
                int failures = 0;
                for (PrioritisedExecutor.Priority curr = this.getPriorityVolatile();;) {
                    if (curr == PrioritisedExecutor.Priority.COMPLETING) {
                        return false;
                    }

                    if (curr.isLowerOrEqualPriority(priority)) {
                        return false;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr == (curr = this.compareAndExchangePriority(curr, priority))) {
                        this.updatePriorityInQueue();
                        return true;
                    }
                    ++failures;
                }
            }

            @Override
            public boolean setPriority(final PrioritisedExecutor.Priority priority) {
                int failures = 0;
                for (PrioritisedExecutor.Priority curr = this.getPriorityVolatile();;) {
                    if (curr == PrioritisedExecutor.Priority.COMPLETING) {
                        return false;
                    }

                    if (curr == priority) {
                        return false;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr == (curr = this.compareAndExchangePriority(curr, priority))) {
                        this.updatePriorityInQueue();
                        return true;
                    }
                    ++failures;
                }
            }

            @Override
            public boolean raisePriority(final PrioritisedExecutor.Priority priority) {
                int failures = 0;
                for (PrioritisedExecutor.Priority curr = this.getPriorityVolatile();;) {
                    if (curr == PrioritisedExecutor.Priority.COMPLETING) {
                        return false;
                    }

                    if (curr.isHigherOrEqualPriority(priority)) {
                        return false;
                    }

                    for (int i = 0; i < failures; ++i) {
                        ConcurrentUtil.backoff();
                    }

                    if (curr == (curr = this.compareAndExchangePriority(curr, priority))) {
                        this.updatePriorityInQueue();
                        return true;
                    }
                    ++failures;
                }
            }

            @Override
            public boolean execute() {
                return this.tryComplete(false);
            }

            @Override
            public boolean cancel() {
                return this.tryComplete(true);
            }
        }
    }
}
