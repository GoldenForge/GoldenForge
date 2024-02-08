package io.papermc.paper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.atomic.AtomicInteger;

public class TickThread extends Thread {

    public static final boolean STRICT_THREAD_CHECKS = Boolean.getBoolean("paper.strict-thread-checks");

    static {
        if (STRICT_THREAD_CHECKS) {
            MinecraftServer.LOGGER.warn("Strict thread checks enabled - performance may suffer");
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public static void softEnsureTickThread(final String reason) {
        if (!STRICT_THREAD_CHECKS) {
            return;
        }
        ensureTickThread(reason);
    }

    /**
     * @deprecated
     */
    @Deprecated
    public static void ensureTickThread(final String reason) {
        if (!isTickThread()) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final ServerLevel world, final BlockPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final ServerLevel world, final ChunkPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final ServerLevel world, final int chunkX, final int chunkZ, final String reason) {
        if (!isTickThreadFor(world, chunkX, chunkZ)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Entity entity, final String reason) {
        if (!isTickThreadFor(entity)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final ServerLevel world, final AABB aabb, final String reason) {
        if (!isTickThreadFor(world, aabb)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final ServerLevel world, final double blockX, final double blockZ, final String reason) {
        if (!isTickThreadFor(world, blockX, blockZ)) {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public final int id; /* We don't override getId as the spec requires that it be unique (with respect to all other threads) */

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger();

    public TickThread(final String name) {
        this(null, name);
    }

    public TickThread(final Runnable run, final String name) {
        this(run, name, ID_GENERATOR.incrementAndGet());
    }

    private TickThread(final Runnable run, final String name, final int id) {
        super(net.neoforged.fml.util.thread.SidedThreadGroups.SERVER, run, name); // Goldenforge
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        return (TickThread) Thread.currentThread();
    }

    public static boolean isTickThread() {
        return Thread.currentThread() instanceof TickThread;
    }

    public static boolean isShutdownThread() {
        return false;
    }

    public static boolean isTickThreadFor(final ServerLevel world, final BlockPos pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final ChunkPos pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final Vec3 pos) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int chunkX, final int chunkZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final AABB aabb) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final double blockX, final double blockZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final Vec3 position, final Vec3 deltaMovement, final int buffer) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int chunkX, final int chunkZ, final int radius) {
        return isTickThread();
    }

    public static boolean isTickThreadFor(final Entity entity) {
        return isTickThread();
    }
}
