package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import com.mojang.logging.LogUtils;
import io.papermc.paper.util.TickThread;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.level.GameRules;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class RegionizedServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RegionizedServer INSTANCE = new RegionizedServer();

    public final RegionizedTaskQueue taskQueue = new RegionizedTaskQueue();

    private final CopyOnWriteArrayList<ServerLevel> worlds = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();

    private final MultiThreadedQueue<Runnable> globalTickQueue = new MultiThreadedQueue<>();

    private final GlobalTickTickHandle tickHandle = new GlobalTickTickHandle(this);

    public static RegionizedServer getInstance() {
        return INSTANCE;
    }

    public void addConnection(final Connection conn) {
        this.connections.add(conn);
    }

    private boolean removeConnection(final Connection conn) {
        return this.connections.remove(conn);
    }

    public void addWorld(final ServerLevel world) {
        this.worlds.add(world);
    }

    public void init() {

        // now we can schedule
        this.tickHandle.setInitialStart(System.nanoTime() + TickRegionScheduler.TIME_BETWEEN_TICKS);
        TickRegions.getScheduler().scheduleRegion(this.tickHandle);
        TickRegions.getScheduler().init();
    }

    public void invalidateStatus() {
        this.lastServerStatus = 0L;
    }

    public void addTaskWithoutNotify(final Runnable run) {
        this.globalTickQueue.add(run);
    }

    public void addTask(final Runnable run) {
        this.addTaskWithoutNotify(run);
        TickRegions.getScheduler().setHasTasks(this.tickHandle);
    }

    /**
     * Returns the current tick of the region ticking.
     * @throws IllegalStateException If there is no current region.
     */
    public static long getCurrentTick() throws IllegalStateException {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            if (TickThread.isShutdownThread()) {
                return 0L;
            }
            throw new IllegalStateException("No currently ticking region");
        }
        return region.getData().getCurrentTick();
    }

    public static boolean isGlobalTickThread() {
        return INSTANCE.tickHandle == TickRegionScheduler.getCurrentTickingTask();
    }

    public static void ensureGlobalTickThread(final String reason) {
        if (!isGlobalTickThread()) {
            throw new IllegalStateException(reason);
        }
    }

    public static TickRegionScheduler.RegionScheduleHandle getGlobalTickData() {
        return INSTANCE.tickHandle;
    }

    private static final class GlobalTickTickHandle extends TickRegionScheduler.RegionScheduleHandle {

        private final RegionizedServer server;

        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean ticking = new AtomicBoolean();

        public GlobalTickTickHandle(final RegionizedServer server) {
            super(null, SchedulerThreadPool.DEADLINE_NOT_SET);
            this.server = server;
        }

        /**
         * Only valid to call BEFORE scheduled!!!!
         */
        final void setInitialStart(final long start) {
            if (this.scheduled.getAndSet(true)) {
                throw new IllegalStateException("Double scheduling global tick");
            }
            this.updateScheduledStart(start);
        }

        @Override
        protected boolean tryMarkTicking() {
            return !this.ticking.getAndSet(true);
        }

        @Override
        protected boolean markNotTicking() {
            return this.ticking.getAndSet(false);
        }

        @Override
        protected void tickRegion(final int tickCount, final long startTime, final long scheduledEnd) {
            this.drainTasks();
            this.server.globalTick(tickCount);
        }

        private void drainTasks() {
            while (this.runOneTask());
        }

        private boolean runOneTask() {
            final Runnable run = this.server.globalTickQueue.poll();
            if (run == null) {
                return false;
            }

            // TODO try catch?
            run.run();

            return true;
        }

        @Override
        protected boolean runRegionTasks(final BooleanSupplier canContinue) {
            do {
                if (!this.runOneTask()) {
                    return false;
                }
            } while (canContinue.getAsBoolean());

            return true;
        }

        @Override
        protected boolean hasIntermediateTasks() {
            return !this.server.globalTickQueue.isEmpty();
        }
    }

    private long lastServerStatus;
    private long tickCount;

    private void globalTick(final int tickCount) {
        ++this.tickCount;
//        // expire invalid click command callbacks
//        io.papermc.paper.adventure.providers.ClickCallbackProviderImpl.CALLBACK_MANAGER.handleQueue((int)this.tickCount);
//
//        // scheduler
//        ((FoliaGlobalRegionScheduler)Bukkit.getGlobalRegionScheduler()).tick();

        // commands
        ((DedicatedServer)MinecraftServer.getServer()).handleConsoleInputs();

        // needs
        // player ping sample
        // world global tick
        // connection tick

        // tick player ping sample
        this.tickPlayerSample();

        // tick worlds
        for (final ServerLevel world : this.worlds) {
            this.globalTick(world, tickCount);
        }

        // tick connections
        this.tickConnections();

        // player list
        MinecraftServer.getServer().getPlayerList().tick();
    }

    private void tickPlayerSample() {
        final MinecraftServer mcServer = MinecraftServer.getServer();

        final long currtime = System.nanoTime();

        // player ping sample
        // copied from MinecraftServer#tickServer
        // note: we need to reorder setPlayers to be the last operation it does, rather than the first to avoid publishing
        // an uncomplete status
        if (currtime - this.lastServerStatus >= 5000000000L) {
            this.lastServerStatus = currtime;
            mcServer.rebuildServerStatus();
        }
    }

    private boolean hasConnectionMovedToMain(final Connection conn) {
        final PacketListener packetListener = conn.getPacketListener();

        return (packetListener instanceof ServerGamePacketListenerImpl) ||
            (packetListener instanceof ServerLoginPacketListenerImpl loginListener && loginListener.state.ordinal() >= ServerLoginPacketListenerImpl.State.HANDING_OFF.ordinal());
    }

    private void tickConnections() {
        final List<Connection> connections = new ArrayList<>(this.connections);
        Collections.shuffle(connections); // shuffle to prevent people from "gaming" the server by re-logging
        for (final Connection conn : connections) {
            if (!conn.becomeActive()) {
                continue;
            }

            if (this.hasConnectionMovedToMain(conn)) {
                if (!conn.isConnected()) {
                    this.removeConnection(conn);
                }
                continue;
            }

            if (!conn.isConnected()) {
                this.removeConnection(conn);
                conn.handleDisconnection();
                continue;
            }

            try {
                conn.tick();
            } catch (final Exception exception) {
                if (conn.isMemoryConnection()) {
                    throw new ReportedException(CrashReport.forThrowable(exception, "Ticking memory connection"));
                }

                LOGGER.warn("Failed to handle packet for {}", io.papermc.paper.configuration.GlobalConfiguration.get().logging.logPlayerIpAddresses ? String.valueOf(conn.getRemoteAddress()) : "<ip address withheld>", exception); // Paper
                MutableComponent ichatmutablecomponent = Component.literal("Internal server error");

                conn.send(new ClientboundDisconnectPacket(ichatmutablecomponent), PacketSendListener.thenRun(() -> {
                    conn.disconnect(ichatmutablecomponent);
                }));
                conn.setReadOnly();
                continue;
            }
        }
    }

    // A global tick only updates things like weather / worldborder, basically anything in the world that is
    // NOT tied to a specific region, but rather shared amongst all of them.
    private void globalTick(final ServerLevel world, final int tickCount) {
        // needs
        // worldborder tick
        // advancing the weather cycle
        // sleep status thing
        // updating sky brightness
        // time ticking (game time + daylight), plus PrimayLevelDat#getScheduledEvents ticking

        // Typically, we expect there to be a running region to drain a world's global chunk tasks. However,
        // this may not be the case - and thus, only the global tick thread can do anything.
        world.taskQueueRegionData.drainGlobalChunkTasks();

        // worldborder tick
        this.tickWorldBorder(world);

        // weather cycle
        this.advanceWeatherCycle(world);

        // sleep status
        this.checkNightSkip(world);

        // update raids
        this.updateRaids(world);

        // sky brightness
        this.updateSkyBrightness(world);

        // time ticking (TODO API synchronisation?)
        this.tickTime(world, tickCount);

        world.updateTickData();
    }

    private void updateRaids(final ServerLevel world) {
        world.getRaids().globalTick();
    }

    private void checkNightSkip(final ServerLevel world) {
        world.tickSleep();
    }

    private void advanceWeatherCycle(final ServerLevel world) {
        world.advanceWeatherCycle();
    }

    private void updateSkyBrightness(final ServerLevel world) {
        world.updateSkyBrightness();
    }

    private void tickWorldBorder(final ServerLevel world) {
        world.getWorldBorder().tick();
    }

    private void tickTime(final ServerLevel world, final int tickCount) {
        if (world.tickTime) {
            if (world.levelData.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
                world.setDayTime(world.levelData.getDayTime() + (long)tickCount);
            }
            world.serverLevelData.setGameTime(world.serverLevelData.getGameTime() + (long)tickCount);
        }
    }

    public static final record WorldLevelData(ServerLevel world, long nonRedstoneGameTime, long dayTime) {

    }
}
