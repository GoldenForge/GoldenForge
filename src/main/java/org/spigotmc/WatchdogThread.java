package org.spigotmc;

import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.goldenforgelauncher.GoldenForgeEntryPoint;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.logging.Level;

public final class WatchdogThread extends io.papermc.paper.util.TickThread // Paper - rewrite chunk system
{

    public static final boolean DISABLE_WATCHDOG = Boolean.getBoolean("disable.watchdog"); // Paper
    private static WatchdogThread instance;
    private long timeoutTime;
    private boolean restart;
    private final long earlyWarningEvery; // Paper - Timeout time for just printing a dump but not restarting
    private final long earlyWarningDelay; // Paper
    public static volatile boolean hasStarted; // Paper
    private long lastEarlyWarning; // Paper - Keep track of short dump times to avoid spamming console with short dumps
    private volatile long lastTick;
    private volatile boolean stopping;

    // Paper start - log detailed tick information
    private void dumpEntity(net.minecraft.world.entity.Entity entity) {
        org.slf4j.Logger log = MinecraftServer.LOGGER;
        double posX, posY, posZ;
        net.minecraft.world.phys.Vec3 mot;
        double moveStartX, moveStartY, moveStartZ;
        net.minecraft.world.phys.Vec3 moveVec;
        synchronized (entity.posLock) {
            posX = entity.getX();
            posY = entity.getY();
            posZ = entity.getZ();
            mot = entity.getDeltaMovement();
            moveStartX = entity.getMoveStartX();
            moveStartY = entity.getMoveStartY();
            moveStartZ = entity.getMoveStartZ();
            moveVec = entity.getMoveVector();
        }

        String entityType = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        java.util.UUID entityUUID = entity.getUUID();
        net.minecraft.world.level.Level world = entity.level;

        log.error("Ticking entity: " + entityType + ", entity class: " + entity.getClass().getName());
        log.error("Entity status: removed: " + entity.isRemoved() + ", valid: " + entity.isValid + ", alive: " + entity.isAlive() + ", is passenger: " + entity.isPassenger());
        log.error("Entity UUID: " + entityUUID);
        log.error("Position: world: '" + (world == null ? "unknown world?" : world.getWorld().getName()) + "' at location (" + posX + ", " + posY + ", " + posZ + ")");
        log.error("Velocity: " + (mot == null ? "unknown velocity" : mot.toString()) + " (in blocks per tick)");
        log.error("Entity AABB: " + entity.getBoundingBox());
        if (moveVec != null) {
            log.error("Move call information: ");
            log.error("Start position: (" + moveStartX + ", " + moveStartY + ", " + moveStartZ + ")");
            log.error("Move vector: " + moveVec.toString());
        }
    }

    private void dumpTickingInfo() {
        org.slf4j.Logger log = MinecraftServer.LOGGER;

        // ticking entities
        for (net.minecraft.world.entity.Entity entity : net.minecraft.server.level.ServerLevel.getCurrentlyTickingEntities()) {
            this.dumpEntity(entity);
            net.minecraft.world.entity.Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                log.error("Detailing vehicle for above entity:");
                this.dumpEntity(vehicle);
            }
        }

        // packet processors
        for (net.minecraft.network.PacketListener packetListener : net.minecraft.network.protocol.PacketUtils.getCurrentPacketProcessors()) {
            if (packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl) {
                net.minecraft.server.level.ServerPlayer player = ((net.minecraft.server.network.ServerGamePacketListenerImpl)packetListener).player;
                long totalPackets = net.minecraft.network.protocol.PacketUtils.getTotalProcessedPackets();
                if (player == null) {
                    log.error("Handling packet for player connection or ticking player connection (null player): " + packetListener);
                    log.error("Total packets processed on the main thread for all players: " + totalPackets);
                } else {
                    this.dumpEntity(player);
                    net.minecraft.world.entity.Entity vehicle = player.getVehicle();
                    if (vehicle != null) {
                        log.error("Detailing vehicle for above entity:");
                        this.dumpEntity(vehicle);
                    }
                    log.error("Total packets processed on the main thread for all players: " + totalPackets);
                }
            } else {
                log.error("Handling packet for connection: " + packetListener);
            }
        }
    }
    // Paper end - log detailed tick information

    private WatchdogThread(long timeoutTime, boolean restart)
    {
        super( "Paper Watchdog Thread" );
        this.timeoutTime = timeoutTime;
        this.restart = restart;
//        earlyWarningEvery = Math.min(io.papermc.paper.configuration.GlobalConfiguration.get().watchdog.earlyWarningEvery, timeoutTime); // Paper
//        earlyWarningDelay = Math.min(io.papermc.paper.configuration.GlobalConfiguration.get().watchdog.earlyWarningDelay, timeoutTime); // Paper
        earlyWarningEvery = 5000;
        earlyWarningDelay = 10000;
    }

    private static long monotonicMillis()
    {
        return System.nanoTime() / 1000000L;
    }

    public static void doStart(int timeoutTime, boolean restart)
    {
        if ( WatchdogThread.instance == null )
        {
            if (timeoutTime <= 0) timeoutTime = 300; // Paper
            WatchdogThread.instance = new WatchdogThread( timeoutTime * 1000L, restart );
            WatchdogThread.instance.start();
        } else
        {
            instance.timeoutTime = timeoutTime * 1000L;
            instance.restart = restart;
        }
    }

    public static void tick()
    {
        instance.lastTick = WatchdogThread.monotonicMillis();
    }

    public static void doStop()
    {
        if ( WatchdogThread.instance != null )
        {
            instance.stopping = true;
        }
    }

    @Override
    public void run()
    {
        while ( !this.stopping )
        {
            //
            // Paper start
            org.slf4j.Logger log = MinecraftServer.LOGGER;
            long currentTime = WatchdogThread.monotonicMillis();
            MinecraftServer server = MinecraftServer.getServer();
            if ( this.lastTick != 0 && this.timeoutTime > 0 && WatchdogThread.hasStarted && (!server.isRunning() || (currentTime > this.lastTick + this.earlyWarningEvery && !DISABLE_WATCHDOG) )) // Paper - add property to disable
            {
                boolean isLongTimeout = currentTime > lastTick + timeoutTime || (!server.isRunning() && !server.hasStopped() && currentTime > lastTick + 1000);
                // Don't spam early warning dumps
                if ( !isLongTimeout && (earlyWarningEvery <= 0 || !hasStarted || currentTime < lastEarlyWarning + earlyWarningEvery || currentTime < lastTick + earlyWarningDelay)) continue;
                if ( !isLongTimeout && server.hasStopped()) continue; // Don't spam early watchdog warnings during shutdown, we'll come back to this...
                lastEarlyWarning = currentTime;
                if (isLongTimeout) {
                // Paper end
                log.error("------------------------------" );
                log.error("The server has stopped responding! This is (probably) not a Goldenforge bug." ); // Paper
                log.error("If you see a mods in the Server thread dump below, then please report it to that author" );
                log.error("\t *Especially* if it looks like HTTP or MySQL operations are occurring" );
                log.error("If you see a world save or edit, then it means you did far more than your server can handle at once" );
                log.error("\t If this is the case, consider increasing timeout-time in goldenforge.toml but note that this will replace the crash with LARGE lag spikes" );
                log.error("If you are unsure or still think this is a Goldenforge bug, please report this to https://github.com/GoldenForge/GoldenForge/issues" );
                log.error("Be sure to include ALL relevant console errors and Minecraft crash reports" );
                log.error("Goldenforge version: " + GoldenForgeEntryPoint.getVersion());
                //
                } else
                {
                    log.error("--- DO NOT REPORT THIS TO GOLDENFORGE - THIS IS NOT A BUG OR A CRASH  - " + GoldenForgeEntryPoint.getVersion() + " ---");
                    log.error("The server has not responded for " + (currentTime - lastTick) / 1000 + " seconds! Creating thread dump");
                }
                // Paper end - Different message for short timeout
                log.error("------------------------------" );
                log.error("Server thread dump (Look for mods here before reporting to Goldenforge!):" ); // Paper
                io.papermc.paper.chunk.system.scheduling.ChunkTaskScheduler.dumpAllChunkLoadInfo(isLongTimeout); // Paper // Paper - rewrite chunk system
                this.dumpTickingInfo(); // Paper - log detailed tick information
                WatchdogThread.dumpThread( ManagementFactory.getThreadMXBean().getThreadInfo( MinecraftServer.getServer().serverThread.getId(), Integer.MAX_VALUE ), log );
                log.error("------------------------------" );
                //
                // Paper start - Only print full dump on long timeouts
                if ( isLongTimeout )
                {
                    log.error("Entire Thread Dump:" );
                ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads( true, true );
                for ( ThreadInfo thread : threads )
                {
                    WatchdogThread.dumpThread( thread, log );
                }
                } else {
                    log.error("--- DO NOT REPORT THIS TO GOLDENFORGE - THIS IS NOT A BUG OR A CRASH ---");
                }

                log.error("------------------------------" );

                if ( isLongTimeout )
                {
                if ( !server.hasStopped() )
                {
                    AsyncCatcher.enabled = false; // Disable async catcher incase it interferes with us
                    AsyncCatcher.shuttingDown = true;
                    server.forceTicks = true;
                    // try one last chance to safe shutdown on main incase it 'comes back'
                    server.abnormalExit = true;
                    server.safeShutdown(false);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (!server.hasStopped()) {
                        server.close();
                    }
                }
                break;
                } // Paper end
            }

            try
            {
                sleep( 1000 ); // Paper - Reduce check time to every second instead of every ten seconds, more consistent and allows for short timeout
            } catch ( InterruptedException ex )
            {
                interrupt();
            }
        }
    }

    private static void dumpThread(ThreadInfo thread, Logger log)
    {
        log.error("------------------------------" );
        //
        log.error("Current Thread: " + thread.getThreadName() );
        log.error("\tPID: " + thread.getThreadId()
                + " | Suspended: " + thread.isSuspended()
                + " | Native: " + thread.isInNative()
                + " | State: " + thread.getThreadState() );
        if ( thread.getLockedMonitors().length != 0 )
        {
            log.error("\tThread is waiting on monitor(s):" );
            for ( MonitorInfo monitor : thread.getLockedMonitors() )
            {
                log.error("\t\tLocked on:" + monitor.getLockedStackFrame() );
            }
        }
        log.error("\tStack:" );
        //
        for ( StackTraceElement stack : thread.getStackTrace() ) // Paper
        {
            log.error("\t\t" + stack );
        }
    }
}
