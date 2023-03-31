package io.papermc.paper.util;

import io.papermc.paper.threadedregions.RegionShutdownThread;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
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
        super(net.minecraftforge.fml.util.thread.SidedThreadGroups.SERVER, run, name);
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        return (TickThread)Thread.currentThread();
    }

    public static boolean isTickThread() {
        return Thread.currentThread() instanceof TickThread;
    }

    public static boolean isShutdownThread() {
        return Thread.currentThread().getClass() == RegionShutdownThread.class;
    }

    public static boolean isTickThreadFor(final ServerLevel world, final BlockPos pos) {
        return isTickThreadFor(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isTickThreadFor(final ServerLevel world, final ChunkPos pos) {
        return isTickThreadFor(world, pos.x, pos.z);
    }

    public static boolean isTickThreadFor(final ServerLevel world, final Vec3 pos) {
        return isTickThreadFor(world, Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4);
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int chunkX, final int chunkZ) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return isShutdownThread();
        }
        return world.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) == region;
    }

    public static boolean isTickThreadFor(final ServerLevel world, final AABB aabb) {
        return isTickThreadFor(
                world,
                CoordinateUtils.getChunkCoordinate(aabb.minX), CoordinateUtils.getChunkCoordinate(aabb.minZ),
                CoordinateUtils.getChunkCoordinate(aabb.maxX), CoordinateUtils.getChunkCoordinate(aabb.maxZ)
        );
    }

    public static boolean isTickThreadFor(final ServerLevel world, final double blockX, final double blockZ) {
        return isTickThreadFor(world, CoordinateUtils.getChunkCoordinate(blockX), CoordinateUtils.getChunkCoordinate(blockZ));
    }

    public static boolean isTickThreadFor(final ServerLevel world, final Vec3 position, final Vec3 deltaMovement, final int buffer) {
        final int fromChunkX = CoordinateUtils.getChunkX(position);
        final int fromChunkZ = CoordinateUtils.getChunkZ(position);

        final int toChunkX = CoordinateUtils.getChunkCoordinate(position.x + deltaMovement.x);
        final int toChunkZ = CoordinateUtils.getChunkCoordinate(position.z + deltaMovement.z);

        // expect from < to, but that may not be the case
        return isTickThreadFor(
                world,
                Math.min(fromChunkX, toChunkX) - buffer,
                Math.min(fromChunkZ, toChunkZ) - buffer,
                Math.max(fromChunkX, toChunkX) + buffer,
                Math.max(fromChunkZ, toChunkZ) + buffer
        );
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return isShutdownThread();
        }

        final int shift = world.regioniser.sectionChunkShift;

        final int minSectionX = fromChunkX >> shift;
        final int maxSectionX = toChunkX >> shift;
        final int minSectionZ = fromChunkZ >> shift;
        final int maxSectionZ = toChunkZ >> shift;

        for (int secZ = minSectionZ; secZ <= maxSectionZ; ++secZ) {
            for (int secX = minSectionX; secX <= maxSectionX; ++secX) {
                final int lowerLeftCX = secX << shift;
                final int lowerLeftCZ = secZ << shift;
                if (world.regioniser.getRegionAtUnsynchronised(lowerLeftCX, lowerLeftCZ) != region) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean isTickThreadFor(final ServerLevel world, final int chunkX, final int chunkZ, final int radius) {
        return isTickThreadFor(world, chunkX - radius, chunkZ - radius, chunkX + radius, chunkZ + radius);
    }

    public static boolean isTickThreadFor(final Entity entity) {
        if (entity == null) {
            return true;
        }
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            if (RegionizedServer.isGlobalTickThread()) {
                if (entity instanceof ServerPlayer serverPlayer) {
                    return serverPlayer.connection == null;
                } else {
                    return false;
                }
            }
            return isShutdownThread();
        }

        final Level level = entity.level;
        if (level != region.regioniser.world) {
            // world mismatch
            return false;
        }

        final RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();

        // pass through the check if the entity is removed and we own its chunk
        if (worldData.hasEntity(entity)) {
            return true;
        }

        if (entity instanceof ServerPlayer serverPlayer) {
            ServerGamePacketListenerImpl conn = serverPlayer.connection;
            return conn != null && worldData.connections.contains(conn.connection);
        } else {
            return ((entity.hasNullCallback() || entity.isRemoved())) && isTickThreadFor((ServerLevel)level, entity.chunkPosition());
        }
    }
}
