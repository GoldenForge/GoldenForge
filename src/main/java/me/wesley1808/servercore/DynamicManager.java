package me.wesley1808.servercore;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MobCategory;

import static me.wesley1808.servercore.DynamicConfig.*;
import static me.wesley1808.servercore.DynamicSetting.*;


public class DynamicManager {
    private final MinecraftServer server;
    private final boolean isClient;
    private double averageTickTime;
    private int count;

    public DynamicManager(MinecraftServer server) {
        this.server = server;
        this.isClient = server.isSingleplayer();

        if (ENABLED.get()) {
            final int maxViewDistance = MAX_VIEW_DISTANCE.get();
            final int maxSimDistance = MAX_SIMULATION_DISTANCE.get();
            if (server.getPlayerList().getViewDistance() > maxViewDistance) {
                this.modifyViewDistance(maxViewDistance);
            }

            if (server.getPlayerList().getSimulationDistance() > maxSimDistance) {
                this.modifySimulationDistance(maxSimDistance);
            }
        }
    }

    public static DynamicManager getInstance(MinecraftServer server) {
        return server.getDynamicManager();
    }

    public static String getModifierAsPercentage() {
        return String.format("%.0f%%", MOBCAP_MULTIPLIER.get() * 100);
    }

    public static void update(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) {
            DynamicManager manager = getInstance(server);
            manager.updateValues();

            if (ENABLED.get()) {
                manager.runPerformanceChecks();
            }
        }
    }

    private void updateValues() {
        this.averageTickTime = this.calculateAverageTickTime();
        this.count++;
    }

    protected double calculateAverageTickTime() {
        return this.server.getAverageTickTime();
    }

    private void runPerformanceChecks() {
        final double targetMspt = TARGET_MSPT.get();
        final boolean decrease = this.averageTickTime > targetMspt + 5;
        final boolean increase = this.averageTickTime < Math.max(targetMspt - 5, 2);

        if (decrease || increase) {
            for (DynamicSetting setting : DynamicSetting.values()) {
                if (setting.shouldRun(this.count) && setting.modify(increase, this)) {
                    break;
                }
            }
        }
    }

    public void modifyViewDistance(int distance) {
        this.server.getPlayerList().setViewDistance(distance);
        if (this.isClient) {
            Minecraft.getInstance().options.renderDistance().set(distance);
        }
    }

    public void modifySimulationDistance(int distance) {
        this.server.getPlayerList().setSimulationDistance(distance);
        if (this.isClient) {
            Minecraft.getInstance().options.simulationDistance().set(distance);
        }
    }

    public void modifyMobcaps(double modifier) {
        for (MobCategory category : MobCategory.values()) {
            if ((Object) category instanceof MobCategory mobCategory) {
                mobCategory.servercore$modifyCapacity(modifier);
            }
        }
    }

    public double getAverageTickTime() {
        return this.averageTickTime;
    }
}