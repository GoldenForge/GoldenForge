package com.tuinity.tuinity.util;

import net.minecraft.server.MinecraftServer;
import org.goldenforge.GoldenForge;

public final class TickThread extends Thread {

    public static final boolean STRICT_THREAD_CHECKS = Boolean.getBoolean("tuinity.strict-thread-checks");

    static {
        if (STRICT_THREAD_CHECKS) {
            MinecraftServer.LOGGER.warn("Strict thread checks enabled - performance may suffer");
        }
    }

    public static void softEnsureTickThread(final String reason) {
        if (!STRICT_THREAD_CHECKS) {
            return;
        }
        ensureTickThread(reason);
    }


    public static void ensureTickThread(final String reason) {
        if (!GoldenForge.isPrimaryThread()) {
            MinecraftServer.LOGGER.fatal("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public final int id; /* We don't override getId as the spec requires that it be unique (with respect to all other threads) */

    public TickThread(final Runnable run, final String name, final int id) {
        super(run, name);
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        return (TickThread)Thread.currentThread();
    }
}