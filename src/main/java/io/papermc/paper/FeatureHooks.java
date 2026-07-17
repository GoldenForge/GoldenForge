package io.papermc.paper;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FeatureHooks {

    // this includes non-accessible entities
    public static Iterable<Entity> getAllEntities(final net.minecraft.server.level.ServerLevel level) {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup)level.getEntities()).getAllMapped(); // Paper - rewrite chunk system
    }

    public static void setPlayerChunkUnloadDelay(final long ticks) {
        ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.setUnloadDelay(ticks); // Paper - rewrite chunk system
    }

//    public static void sendChunkRefreshPackets(final List<ServerPlayer> playersInRange, final LevelChunk chunk) {
//        // Paper start - Anti-Xray
//        final Map<Object, ClientboundLevelChunkWithLightPacket> refreshPackets = new HashMap<>();
//        for (final ServerPlayer player : playersInRange) {
//            if (player.connection == null) continue;
//
//            final Boolean shouldModify = chunk.getLevel().chunkPacketBlockController.shouldModify(player, chunk);
//            player.connection.send(refreshPackets.computeIfAbsent(shouldModify, s -> { // Use connection to prevent creating firing event
//                return new ClientboundLevelChunkWithLightPacket(chunk, chunk.getLevel().getLightEngine(), null, null, (Boolean) s);
//            }));
//            // Paper end - Anti-Xray
//        }
//    }


    public static int getViewDistance(net.minecraft.server.level.ServerLevel level) {
        return level.moonrise$getPlayerChunkLoader().getAPIViewDistance(); // Paper - rewrite chunk system
    }

    public static int getSimulationDistance(net.minecraft.server.level.ServerLevel level) {
        return level.moonrise$getPlayerChunkLoader().getAPITickDistance(); // Paper - rewrite chunk system
    }

    public static int getSendViewDistance(net.minecraft.server.level.ServerLevel level) {
        return level.moonrise$getPlayerChunkLoader().getAPISendViewDistance(); // Paper - rewrite chunk system
    }

    public static void setViewDistance(net.minecraft.server.level.ServerLevel level, int distance) {
        if (distance < 2 || distance > 32) {
            throw new IllegalArgumentException("View distance " + distance + " is out of range of [2, 32]");
        }
        level.getChunkSource().chunkMap.setServerViewDistance(distance);
    }

    public static void setSimulationDistance(net.minecraft.server.level.ServerLevel level, int distance) {
        if (distance < 2 || distance > 32) {
            throw new IllegalArgumentException("Simulation distance " + distance + " is out of range of [2, 32]");
        }
        level.getChunkSource().chunkMap.getDistanceManager().updateSimulationDistance(distance);
    }

    public static java.util.concurrent.Executor getWorldgenExecutor() {
        return Runnable::run; // Paper - rewrite chunk system
    }

    public static void setViewDistance(ServerPlayer player, int distance) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer)player).moonrise$getViewDistanceHolder().setLoadViewDistance(distance == -1 ? distance : distance + 1); // Paper - rewrite chunk system
    }

    public static void setSimulationDistance(ServerPlayer player, int distance) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer)player).moonrise$getViewDistanceHolder().setTickViewDistance(distance); // Paper - rewrite chunk system
    }

    public static void setSendViewDistance(ServerPlayer player, int distance) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer)player).moonrise$getViewDistanceHolder().setSendViewDistance(distance); // Paper - rewrite chunk system
    }
}