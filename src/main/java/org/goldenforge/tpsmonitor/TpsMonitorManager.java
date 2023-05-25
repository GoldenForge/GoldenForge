package org.goldenforge.tpsmonitor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class TpsMonitorManager {
    private List<ServerPlayer> activesTpsMonitors = new ArrayList<>();
    private static TpsMonitorManager INSTANCE;
    private DecimalFormat FORMATER = new DecimalFormat("#.##");

    public TpsMonitorManager() {
        INSTANCE = this;
        MinecraftServer.getServer().addTickable(this::tick);
    }


    public void togglePlayer(ServerPlayer player) {
        if (activesTpsMonitors.contains(player)) {
            activesTpsMonitors.remove(player);
            player.sendSystemMessage(Component.literal(ChatFormatting.GOLD + "Disabling TpsMonitor"));

        } else {
            activesTpsMonitors.add(player);
            player.sendSystemMessage(Component.literal(ChatFormatting.GOLD + "Enabling TpsMonitor"));


        }
    }

    private static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.00");
    });

    public void tick() {

        double tps = MinecraftServer.getServer().getTPS()[0];
        if (tps > 20.0D) {
            tps = 20.0D;
        } else if (tps < 0.0D) {
            tps = 0.0D;
        }

        String tpsColor;
        if (tps >= 18) {
            tpsColor = "§2";
        } else if (tps >= 15) {
            tpsColor = "§e";
        } else {
            tpsColor = "§4";
        }

        double mspt = MinecraftServer.getServer().getAverageTickTime();
        String msptColor;
        if (mspt < 40) {
            msptColor = "§2";
        } else if (mspt < 50) {
            msptColor = "§e";
        } else {
            msptColor = "§4";
        }

        final long currTime = System.nanoTime();
        final double genRate = io.papermc.paper.chunk.system.scheduling.ChunkFullTask.genRate(currTime);
        final double loadRate = io.papermc.paper.chunk.system.scheduling.ChunkFullTask.loadRate(currTime);

        String msg = ChatFormatting.DARK_GRAY + "TPS: " + tpsColor + FORMATER.format(tps) + ChatFormatting.DARK_GRAY + " MSPT: " + msptColor + FORMATER.format(mspt) + ChatFormatting.DARK_GRAY + " LOAD/GEN RATE: " + TWO_DECIMAL_PLACES.get().format(loadRate) +"/"+TWO_DECIMAL_PLACES.get().format(genRate);


        ClientboundSetActionBarTextPacket packet = new ClientboundSetActionBarTextPacket(Component.literal(msg));

        for (ServerPlayer player : activesTpsMonitors) {
            player.connection.send(packet);
        }
    }

    public static TpsMonitorManager get() {
        return INSTANCE;
    }
}