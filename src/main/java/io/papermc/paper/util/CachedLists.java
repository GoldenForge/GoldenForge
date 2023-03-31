package io.papermc.paper.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.util.UnsafeList;
import java.util.List;

public final class CachedLists {

    // Paper start - optimise collisions
    // Folia - region threading

    public static UnsafeList<AABB> getTempCollisionList() {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return new UnsafeList<>(16);
        }
        return worldData.tempCollisionList.get();
        // Folia end - region threading
    }

    public static void returnTempCollisionList(List<AABB> list) {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return;
        }
        worldData.tempCollisionList.ret(list);
        // Folia end - region threading
    }

    // Folia - region threading

    public static UnsafeList<Entity> getTempGetEntitiesList() {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return new UnsafeList<>(16);
        }
        return worldData.tempEntitiesList.get();
        // Folia end - region threading
    }

    public static void returnTempGetEntitiesList(List<Entity> list) {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return;
        }
        worldData.tempEntitiesList.ret(list);
        // Folia end - region threading
    }
    // Paper end - optimise collisions

    public static void reset() {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData != null) {
            worldData.resetCollisionLists();
        }
        // Folia end - region threading
    }
}
