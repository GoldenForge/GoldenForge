package org.goldenforge;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goldenforge.scheduler.GoldenScheduler;

public class GoldenForge {
    public static final Logger LOGGER = LogManager.getLogger("GoldenForge");

    private static String name = "GoldenForge";

//    public static GoldenScheduler getScheduler() {
//        return MinecraftServer.getServer().getScheduler();
//    }

    public static String getName() {
        return name;
    }

    public static String getVersion() {
        return GoldenForgeEntryPoint.getVersion();
    }

    public static final boolean isEnabled() {
        return true;
    }

    public static boolean isStopping() {
        //return MinecraftServer.getServer().hasStopped();
        return false;
        //TODO:
    }

    public static boolean isPrimaryThread() {
        // Tuinity start
        final Thread currThread = Thread.currentThread();
        return currThread == MinecraftServer.getServer().serverThread || currThread instanceof com.tuinity.tuinity.util.TickThread || currThread.equals(net.minecraft.server.MinecraftServer.getServer().shutdownThread); // Paper - Fix issues with detecting main thread properly, the only time Watchdog will be used is during a crash shutdown which is a "try our best" scenario
        // Tuinity End
        }
}
