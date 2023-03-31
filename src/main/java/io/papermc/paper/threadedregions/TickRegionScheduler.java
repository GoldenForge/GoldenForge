package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
import com.mojang.logging.LogUtils;
import io.papermc.paper.util.TickThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class TickRegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean MEASURE_CPU_TIME;
    static {
        MEASURE_CPU_TIME = THREAD_MX_BEAN.isThreadCpuTimeSupported();
        if (MEASURE_CPU_TIME) {
            THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
        } else {
            LOGGER.warn("TickRegionScheduler CPU time measurement is not available");
        }
    }

    public static final int TICK_RATE = 20;
    public static final long TIME_BETWEEN_TICKS = 1_000_000_000L / TICK_RATE; // ns

    private final SchedulerThreadPool scheduler;

    public TickRegionScheduler(final int threads) {
        this.scheduler = new SchedulerThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger idGenerator = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable run) {
                final Thread ret = new TickThreadRunner(run, "Region Scheduler Thread #" + this.idGenerator.getAndIncrement());
                ret.setUncaughtExceptionHandler(TickRegionScheduler.this::uncaughtException);
                return ret;
            }
        });
    }

    public int getTotalThreadCount() {
        return this.scheduler.getThreads().length;
    }

    private static void setTickingRegion(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            throw new IllegalStateException("Must be tick thread runner");
        }
        if (region != null && tickThreadRunner.currentTickingRegion != null) {
            throw new IllegalStateException("Trying to double set ticking region!");
        }
        if (region == null && tickThreadRunner.currentTickingRegion == null) {
            throw new IllegalStateException("Trying to double unset ticking region!");
        }
        tickThreadRunner.currentTickingRegion = region;
        if (region != null) {
            tickThreadRunner.currentTickingWorldRegionizedData = region.regioniser.world.worldRegionData.get();
        } else {
            tickThreadRunner.currentTickingWorldRegionizedData = null;
        }
    }

    private static void setTickTask(final SchedulerThreadPool.SchedulableTick task) {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            throw new IllegalStateException("Must be tick thread runner");
        }
        if (task != null && tickThreadRunner.currentTickingTask != null) {
            throw new IllegalStateException("Trying to double set ticking task!");
        }
        if (task == null && tickThreadRunner.currentTickingTask == null) {
            throw new IllegalStateException("Trying to double unset ticking task!");
        }
        tickThreadRunner.currentTickingTask = task;
    }

    /**
     * Returns the current ticking region, or {@code null} if there is no ticking region.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> getCurrentRegion() {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            return RegionShutdownThread.getRegion();
        }
        return tickThreadRunner.currentTickingRegion;
    }

    /**
     * Returns the current ticking region's world regionised data, or {@code null} if there is no ticking region.
     * This is a faster alternative to calling the {@link RegionizedData#get()} method.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static RegionizedWorldData getCurrentRegionizedWorldData() {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            return RegionShutdownThread.getWorldData();
        }
        return tickThreadRunner.currentTickingWorldRegionizedData;
    }

    /**
     * Returns the current ticking task, or {@code null} if there is no ticking region.
     * If this thread is not a TickThread, then returns {@code null}.
     */
    public static SchedulerThreadPool.SchedulableTick getCurrentTickingTask() {
        final Thread currThread = Thread.currentThread();
        if (!(currThread instanceof TickThreadRunner tickThreadRunner)) {
            return null;
        }
        return tickThreadRunner.currentTickingTask;
    }

    /**
     * Schedules the given region
     * @throws IllegalStateException If the region is already scheduled or is ticking
     */
    public void scheduleRegion(final RegionScheduleHandle region) {
        region.scheduler = this;
        this.scheduler.schedule(region);
    }

    /**
     * Attempts to de-schedule the provided region. If the current region cannot be cancelled for its next tick or task
     * execution, then it will be cancelled after.
     */
    public void descheduleRegion(final RegionScheduleHandle region) {
        // To avoid acquiring any of the locks the scheduler may be using, we
        // simply cancel the next action.
        region.markNonSchedulable();
    }

    /**
     * Updates the tick start to the farthest into the future of its current scheduled time and the
     * provided time.
     * @return {@code false} if the region was not scheduled or is currently ticking or the specified time is less-than its
     *                       current start time, {@code true} if the next tick start was adjusted.
     */
    public boolean updateTickStartToMax(final RegionScheduleHandle region, final long newStart) {
        return this.scheduler.updateTickStartToMax(region, newStart);
    }

    public boolean halt(final boolean sync, final long maxWaitNS) {
        return this.scheduler.halt(sync, maxWaitNS);
    }

    public void setHasTasks(final RegionScheduleHandle region) {
        this.scheduler.notifyTasks(region);
    }

    public void init() {
        this.scheduler.start();
    }

    private void uncaughtException(final Thread thread, final Throwable thr) {
        LOGGER.error("Uncaught exception in tick thread \"" + thread.getName() + "\"", thr);

        // prevent further ticks from occurring
        // we CANNOT sync, because WE ARE ON A SCHEDULER THREAD
        this.scheduler.halt(false, 0L);

        MinecraftServer.getServer().stopServer();
    }

    private void regionFailed(final RegionScheduleHandle handle, final boolean executingTasks, final Throwable thr) {
        // when a region fails, we need to shut down the server gracefully

        // prevent further ticks from occurring
        // we CANNOT sync, because WE ARE ON A SCHEDULER THREAD
        this.scheduler.halt(false, 0L);

        final ChunkPos center = handle.region == null ? null : handle.region.region.getCenterChunk();

        LOGGER.error("Region #" + (handle.region == null ? -1L : handle.region.id) + " centered at chunk " + center + " failed to " + (executingTasks ? "execute tasks" : "tick") + ":", thr);

        MinecraftServer.getServer().stopServer();
    }

    // By using our own thread object, we can use a field for the current region rather than a ThreadLocal.
    // This is much faster than a thread local, since the thread local has to use a map lookup.
    private static final class TickThreadRunner extends TickThread {

        private ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentTickingRegion;
        private RegionizedWorldData currentTickingWorldRegionizedData;
        private SchedulerThreadPool.SchedulableTick currentTickingTask;

        public TickThreadRunner(final Runnable run, final String name) {
            super(run, name);
        }
    }

    public static abstract class RegionScheduleHandle extends SchedulerThreadPool.SchedulableTick {

        protected long currentTick;
        protected long lastTickStart;

        protected final TickData tickTimes5s;
        protected final TickData tickTimes15s;
        protected final TickData tickTimes1m;
        protected final TickData tickTimes5m;
        protected final TickData tickTimes15m;
        protected TickTime currentTickData;
        protected Thread currentTickingThread;

        public final TickRegions.TickRegionData region;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        protected final Schedule tickSchedule;

        private TickRegionScheduler scheduler;

        public RegionScheduleHandle(final TickRegions.TickRegionData region, final long firstStart) {
            this.currentTick = 0L;
            this.lastTickStart = SchedulerThreadPool.DEADLINE_NOT_SET;
            this.tickTimes5s = new TickData(TimeUnit.SECONDS.toNanos(5L));
            this.tickTimes15s = new TickData(TimeUnit.SECONDS.toNanos(15L));
            this.tickTimes1m = new TickData(TimeUnit.MINUTES.toNanos(1L));
            this.tickTimes5m = new TickData(TimeUnit.MINUTES.toNanos(5L));
            this.tickTimes15m = new TickData(TimeUnit.MINUTES.toNanos(15L));
            this.region = region;

            this.setScheduledStart(firstStart);
            this.tickSchedule = new Schedule(firstStart == SchedulerThreadPool.DEADLINE_NOT_SET ? firstStart : firstStart - TIME_BETWEEN_TICKS);
        }

        /**
         * Subclasses should call this instead of {@link ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool.SchedulableTick#setScheduledStart(long)}
         * so that the tick schedule and scheduled start remain synchronised
         */
        protected final void updateScheduledStart(final long to) {
            this.setScheduledStart(to);
            this.tickSchedule.setLastPeriod(to == SchedulerThreadPool.DEADLINE_NOT_SET ? to : to - TIME_BETWEEN_TICKS);
        }

        public final void markNonSchedulable() {
            this.cancelled.set(true);
        }

        public final boolean isMarkedAsNonSchedulable() {
            return this.cancelled.get();
        }

        protected abstract boolean tryMarkTicking();

        protected abstract boolean markNotTicking();

        protected abstract void tickRegion(final int tickCount, final long startTime, final long scheduledEnd);

        protected abstract boolean runRegionTasks(final BooleanSupplier canContinue);

        protected abstract boolean hasIntermediateTasks();

        @Override
        public final boolean hasTasks() {
            return this.hasIntermediateTasks();
        }

        @Override
        public final Boolean runTasks(final BooleanSupplier canContinue) {
            if (this.cancelled.get()) {
                return null;
            }

            final long cpuStart = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
            final long tickStart = System.nanoTime();

            if (!this.tryMarkTicking()) {
                if (!this.cancelled.get()) {
                    throw new IllegalStateException("Scheduled region should be acquirable");
                }
                // region was killed
                return null;
            }

            TickRegionScheduler.setTickTask(this);
            if (this.region != null) {
                TickRegionScheduler.setTickingRegion(this.region.region);
            }

            synchronized (this) {
                this.currentTickData = new TickTime(
                    SchedulerThreadPool.DEADLINE_NOT_SET, SchedulerThreadPool.DEADLINE_NOT_SET, tickStart, cpuStart,
                    SchedulerThreadPool.DEADLINE_NOT_SET, SchedulerThreadPool.DEADLINE_NOT_SET, MEASURE_CPU_TIME,
                    false
                );
                this.currentTickingThread = Thread.currentThread();
            }

            final boolean ret;
            try {
                ret = this.runRegionTasks(() -> {
                    return !RegionScheduleHandle.this.cancelled.get() && canContinue.getAsBoolean();
                });
            } catch (final Throwable thr) {
                this.scheduler.regionFailed(this, true, thr);
                if (thr instanceof ThreadDeath) {
                    throw (ThreadDeath)thr;
                }
                // don't release region for another tick
                return null;
            } finally {
                TickRegionScheduler.setTickTask(null);
                if (this.region != null) {
                    TickRegionScheduler.setTickingRegion(null);
                }
                final long tickEnd = System.nanoTime();
                final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;

                final TickTime time = new TickTime(
                    SchedulerThreadPool.DEADLINE_NOT_SET, SchedulerThreadPool.DEADLINE_NOT_SET,
                    tickStart, cpuStart, tickEnd, cpuEnd, MEASURE_CPU_TIME, false
                );

                this.addTickTime(time);
            }

            return !this.markNotTicking() || this.cancelled.get() ? null : Boolean.valueOf(ret);
        }

        @Override
        public final boolean runTick() {
            // Remember, we are supposed use setScheduledStart if we return true here, otherwise
            // the scheduler will try to schedule for the same time.
            if (this.cancelled.get()) {
                return false;
            }

            final long cpuStart = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;
            final long tickStart = System.nanoTime();

            // use max(), don't assume that tickStart >= scheduledStart
            final int tickCount = Math.max(1, this.tickSchedule.getPeriodsAhead(TIME_BETWEEN_TICKS, tickStart));

            if (!this.tryMarkTicking()) {
                if (!this.cancelled.get()) {
                    throw new IllegalStateException("Scheduled region should be acquirable");
                }
                // region was killed
                return false;
            }
            if (this.cancelled.get()) {
                this.markNotTicking();
                // region should be killed
                return false;
            }

            TickRegionScheduler.setTickTask(this);
            if (this.region != null) {
                TickRegionScheduler.setTickingRegion(this.region.region);
            }
            this.incrementTickCount();
            final long lastTickStart = this.lastTickStart;
            this.lastTickStart = tickStart;

            final long scheduledStart = this.getScheduledStart();
            final long scheduledEnd = scheduledStart + TIME_BETWEEN_TICKS;

            synchronized (this) {
                this.currentTickData = new TickTime(
                    lastTickStart, scheduledStart, tickStart, cpuStart,
                    SchedulerThreadPool.DEADLINE_NOT_SET, SchedulerThreadPool.DEADLINE_NOT_SET, MEASURE_CPU_TIME,
                    true
                );
                this.currentTickingThread = Thread.currentThread();
            }

            try {
                // next start isn't updated until the end of this tick
                this.tickRegion(tickCount, tickStart, scheduledEnd);
            } catch (final Throwable thr) {
                this.scheduler.regionFailed(this, false, thr);
                if (thr instanceof ThreadDeath) {
                    throw (ThreadDeath)thr;
                }
                // regionFailed will schedule a shutdown, so we should avoid letting this region tick further
                return false;
            } finally {
                TickRegionScheduler.setTickTask(null);
                if (this.region != null) {
                    TickRegionScheduler.setTickingRegion(null);
                }
                final long tickEnd = System.nanoTime();
                final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : 0L;

                // in order to ensure all regions get their chance at scheduling, we have to ensure that regions
                // that exceed the max tick time are not always prioritised over everything else. Thus, we use the greatest
                // of the current time and "ideal" next tick start.
                this.tickSchedule.advanceBy(tickCount, TIME_BETWEEN_TICKS);
                this.setScheduledStart(TimeUtil.getGreatestTime(tickEnd, this.tickSchedule.getDeadline(TIME_BETWEEN_TICKS)));

                final TickTime time = new TickTime(
                    lastTickStart, scheduledStart, tickStart, cpuStart, tickEnd, cpuEnd, MEASURE_CPU_TIME, true
                );

                this.addTickTime(time);
            }

            // Only AFTER updating the tickStart
            return this.markNotTicking() && !this.cancelled.get();
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        private void addTickTime(final TickTime time) {
            synchronized (this) {
                this.currentTickData = null;
                this.currentTickingThread = null;
                this.tickTimes5s.addDataFrom(time);
                this.tickTimes15s.addDataFrom(time);
                this.tickTimes1m.addDataFrom(time);
                this.tickTimes5m.addDataFrom(time);
                this.tickTimes15m.addDataFrom(time);
            }
        }

        private TickTime adjustCurrentTickData(final long tickEnd) {
            final TickTime currentTickData = this.currentTickData;
            if (currentTickData == null) {
                return null;
            }

            final long cpuEnd = MEASURE_CPU_TIME ? THREAD_MX_BEAN.getThreadCpuTime(this.currentTickingThread.getId()) : 0L;

            return new TickTime(
                currentTickData.previousTickStart(), currentTickData.scheduledTickStart(),
                currentTickData.tickStart(), currentTickData.tickStartCPU(),
                tickEnd, cpuEnd,
                MEASURE_CPU_TIME, currentTickData.isTickExecution()
            );
        }

        public final TickData.TickReportData getTickReport5s(final long currTime) {
            synchronized (this) {
                return this.tickTimes5s.generateTickReport(this.adjustCurrentTickData(currTime), currTime);
            }
        }

        public final TickData.TickReportData getTickReport15s(final long currTime) {
            synchronized (this) {
                return this.tickTimes15s.generateTickReport(this.adjustCurrentTickData(currTime), currTime);
            }
        }

        public final TickData.TickReportData getTickReport1m(final long currTime) {
            synchronized (this) {
                return this.tickTimes1m.generateTickReport(this.adjustCurrentTickData(currTime), currTime);
            }
        }

        public final TickData.TickReportData getTickReport5m(final long currTime) {
            synchronized (this) {
                return this.tickTimes5m.generateTickReport(this.adjustCurrentTickData(currTime), currTime);
            }
        }

        public final TickData.TickReportData getTickReport15m(final long currTime) {
            synchronized (this) {
                return this.tickTimes15m.generateTickReport(this.adjustCurrentTickData(currTime), currTime);
            }
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        private void incrementTickCount() {
            ++this.currentTick;
        }

        /**
         * Only safe to call if this tick data matches the current ticking region.
         */
        public final long getCurrentTick() {
            return this.currentTick;
        }

        protected final void setCurrentTick(final long value) {
            this.currentTick = value;
        }
    }

    // All time units are in nanoseconds.
    public static final record TickTime(
        long previousTickStart,
        long scheduledTickStart,
        long tickStart,
        long tickStartCPU,
        long tickEnd,
        long tickEndCPU,
        boolean supportCPUTime,
        boolean isTickExecution
    ) {
        /**
         * The difference between the start tick time and the scheduled start tick time. This value is
         * < 0 if the tick started before the scheduled tick time.
         * Only valid when {@link #isTickExecution()} is {@code true}.
         */
        public final long startOvershoot() {
            return this.tickStart - this.scheduledTickStart;
        }

        /**
         * The difference from the end tick time and the start tick time. Always >= 0 (unless nanoTime is just wrong).
         */
        public final long tickLength() {
            return this.tickEnd - this.tickStart;
        }

        /**
         * The total CPU time from the start tick time to the end tick time. Generally should be equal to the tickLength,
         * unless there is CPU starvation or the tick thread was blocked by I/O or other tasks. Returns Long.MIN_VALUE
         * if CPU time measurement is not supported.
         */
        public final long tickCpuTime() {
            if (!this.supportCPUTime()) {
                return Long.MIN_VALUE;
            }
            return this.tickEndCPU - this.tickStartCPU;
        }

        /**
         * The difference in time from the start of the last tick to the start of the current tick. If there is no
         * last tick, then this value is max(TIME_BETWEEN_TICKS, tickLength).
         * Only valid when {@link #isTickExecution()} is {@code true}.
         */
        public final long differenceFromLastTick() {
            if (this.hasLastTick()) {
                return this.tickStart - this.previousTickStart;
            }
            return Math.max(TIME_BETWEEN_TICKS, this.tickLength());
        }

        /**
         * Returns whether there was a tick that occurred before this one.
         * Only valid when {@link #isTickExecution()} is {@code true}.
         */
        public boolean hasLastTick() {
            return this.previousTickStart != SchedulerThreadPool.DEADLINE_NOT_SET;
        }

        /*
         * Remember, this is the expected behavior of the following:
         *
         * MSPT: Time per tick. This does not include overshoot time, just the tickLength().
         *
         * TPS: The number of ticks per second. It should be ticks / (sum of differenceFromLastTick).
         */
    }
}
