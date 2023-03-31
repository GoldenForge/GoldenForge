package io.papermc.paper.threadedregions;

import com.mojang.logging.LogUtils;
import io.papermc.paper.util.TickThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RegionShutdownThread extends TickThread {

    private static final Logger LOGGER = LogUtils.getLogger();

    ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> shuttingDown;

    public RegionShutdownThread(final String name) {
        super(name);
        this.setUncaughtExceptionHandler((thread, thr) -> {
            LOGGER.error("Error shutting down server", thr);
        });
    }

    static ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> getRegion() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof RegionShutdownThread shutdownThread) {
            return shutdownThread.shuttingDown;
        }
        return null;
    }


    static RegionizedWorldData getWorldData() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof RegionShutdownThread shutdownThread) {
            // no fast path for shutting down
            if (shutdownThread.shuttingDown != null) {
                return shutdownThread.shuttingDown.getData().world.worldRegionData.get();
            }
        }
        return null;
    }

    // The region shutdown thread bypasses all tick thread checks, which will allow us to execute global saves
    // it will not however let us perform arbitrary sync loads, arbitrary world state lookups simply because
    // the data required to do that is regionised, and we can only access it when we OWN the region, and we do not.
    // Thus, the only operation that the shutdown thread will perform

    private void saveLevelData(final ServerLevel world) {
        try {
            world.saveLevelData();
        } catch (final Throwable thr) {
            LOGGER.error("Failed to save level data for " + world.getWorld().getName(), thr);
        }
    }

    private void finishTeleportations(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
                                      final ServerLevel world) {
        try {
            this.shuttingDown = region;
            final List<ServerLevel.PendingTeleport> pendingTeleports = world.removeAllRegionTeleports();
            if (pendingTeleports.isEmpty()) {
                return;
            }
            final ChunkPos center = region.getCenterChunk();
            LOGGER.info("Completing " + pendingTeleports.size() + " pending teleports in region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'");
            for (final ServerLevel.PendingTeleport pendingTeleport : pendingTeleports) {
                LOGGER.info("Completing teleportation to target position " + pendingTeleport.to());

                // first, add entities to entity chunk so that they will be saved
                for (final Entity.EntityTreeNode node : pendingTeleport.rootVehicle().getFullTree()) {
                    // assume that world and position are set to destination here
                    node.root.level = world; // in case the pending teleport is from a portal before it finds the exact destination
                    world.getEntityLookup().addEntityForShutdownTeleportComplete(node.root);
                }

                // then, rebuild the passenger tree so that when saving only the root vehicle will be written - and if
                // there are any player passengers, that the later player saving will save the tree
                pendingTeleport.rootVehicle().restore();

                // now we are finished
                LOGGER.info("Completed teleportation to target position " + pendingTeleport.to());
            }
        } catch (final Throwable thr) {
            LOGGER.error("Failed to complete pending teleports", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    private void saveRegionChunks(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
                                  final boolean last) {
        ChunkPos center = null;
        try {
            this.shuttingDown = region;
            center = region.getCenterChunk();
            LOGGER.info("Saving chunks around region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'");
            region.regioniser.world.chunkTaskScheduler.chunkHolderManager.close(true, true, false, last, false);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to save chunks for region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    private void haltChunkSystem(final ServerLevel world) {
        try {
            world.chunkTaskScheduler.chunkHolderManager.close(false, true, true, false, false);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to halt chunk system for world '" + world.getWorld().getName() + "'", thr);
        }
    }

    private void haltWorldNoRegions(final ServerLevel world) {
        try {
            world.chunkTaskScheduler.chunkHolderManager.close(true, true, true, true, false);
        } catch (final Throwable thr) {
            LOGGER.error("Failed to close world '" + world.getWorld().getName() + "' with no regions", thr);
        }
    }

    @Override
    public final void run() {
        // await scheduler termination
        LOGGER.info("Awaiting scheduler termination for 60s");
        if (TickRegions.getScheduler().halt(true, TimeUnit.SECONDS.toNanos(60L))) {
            LOGGER.info("Scheduler halted");
        } else {
            LOGGER.warn("Scheduler did not terminate within 60s, proceeding with shutdown anyways");
        }

        MinecraftServer.getServer().stopServer(); // stop part 1: most logic, kicking players, plugins, etc
        // halt all chunk systems first so that any in-progress chunk generation stops
        LOGGER.info("Halting chunk systems");
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            try {
                world.chunkTaskScheduler.halt(false, 0L);
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to soft halt chunk system for world '" + world.getWorld().getName() + "'", throwable);
            }
        }
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            this.haltChunkSystem(world);
        }
        LOGGER.info("Halted chunk systems");
        for (final ServerLevel world : MinecraftServer.getServer().getAllLevels()) {
            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>>
                regions = new ArrayList<>();
            world.regioniser.computeForAllRegionsUnsynchronised(regions::add);

            for (int i = 0, len = regions.size(); i < len; ++i) {
                final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = regions.get(i);
                this.finishTeleportations(region, world);
            }

            for (int i = 0, len = regions.size(); i < len; ++i) {
                final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = regions.get(i);
                this.saveRegionChunks(region, (i + 1) == len);
            }

            this.saveLevelData(world);
        }
        // moved from stop part 1
        // we need this to be after saving level data, as that will complete any teleportations the player is in
        LOGGER.info("Saving players");
        MinecraftServer.getServer().getPlayerList().saveAll();

        MinecraftServer.getServer().stopPart2(); // stop part 2: close other resources (io thread, etc)
        // done, part 2 should call exit()
    }
}
