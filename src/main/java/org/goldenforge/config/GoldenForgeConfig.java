/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package org.goldenforge.config;

import static net.minecraftforge.fml.Logging.FORGEMOD;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;

import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import org.bukkit.Bukkit;
import org.goldenforge.GoldenForge;

import java.util.logging.Level;


public class GoldenForgeConfig {
    public static class Server {

        public static ConfigValue<Integer> ioThreads;
        public static ConfigValue<Integer> workerThreads;

        public static ConfigValue<Integer> minLoadRadius;
        public static ConfigValue<Integer> maxConcurrentSends;
        public static ConfigValue<Boolean> autoconfigSendDistance;
        public static ConfigValue<Double> targetPlayerChunkSendRate;
        public static ConfigValue<Double> globalMaxChunkSendRate;
        public static ConfigValue<Boolean> enableFrustumPriority;
        public static ConfigValue<Double> globalMaxChunkLoadRate;
        public static ConfigValue<Double> playerMaxConcurrentLoads;
        public static ConfigValue<Double> globalMaxConcurrentLoads;
        public static ConfigValue<Double> playerMaxChunkLoadRate;


        public static ConfigValue<Boolean> isVelocityEnabled;
        public static ConfigValue<String> velocityForwardingToken;

        public static ConfigValue<Integer> maxJoinsPerTick;
        public static ConfigValue<Boolean> preventMovingIntoUnloadedChunks;
        public static ConfigValue<Boolean> enableAsyncMobSpawning;
        public static ConfigValue<Boolean> perPlayerMobSpawns;

        public static ConfigValue<Integer> autoSaveInterval;
        public static ConfigValue<Integer> maxAutoSaveChunksPerTick;

        public static ConfigValue<Integer> chatExecutorCoreSize;
        public static ConfigValue<Integer> chatExecutorMaxSize;
        public static ConfigValue<Integer> loginTicks;
        public static ConfigValue<Boolean> optimizeExplosions;
        public static ConfigValue<Integer> nettyThreads;
        Server(ForgeConfigSpec.Builder builder) {
            builder.comment("GoldenForge Configuration")
                    .push("ChunkSystem");

            ioThreads = builder
                    .comment("he number of threads the server should use for world saving and chunk loading. The default (-1) indicates that Paper will utilize half of your system's threads for chunk loading unless otherwise specified. There is also a maximum default of 4 threads used for saving and loading chunks. This can be overridden by adding -Dpaper.maxChunkThreads=[number] to your startup arguments")
                    .worldRestart()
                    .define("ioThreads", -1);


            workerThreads = builder
                    .comment("he number of threads the server should use for world generation loading chunks.")
                    .worldRestart()
                    .define("workerThreads", -1);

            maxConcurrentSends = builder
                    .comment("The maximum number of chunks that will be queued to send at any one time. Lower values will help alleviate server-side networking bottlenecks such as anti-xray or compression; however, it is unlikely to help users with a poor internet connection.")
                    .worldRestart()
                    .define("maxConcurrentSends", 2);


            minLoadRadius = builder
                    .comment("The radius of chunks around a player that are not throttled for chunk loading. The number of chunks affected is actually the configured value plus one, as this config controls the number of chunks the client will actually be able to render.")
                    .worldRestart()
                    .define("minLoadRadius", 2);

            maxConcurrentSends = builder
                    .comment("The maximum number of chunks that will be queued to send at any one time. Lower values will help alleviate server-side networking bottlenecks such as anti-xray or compression; however, it is unlikely to help users with a poor internet connection.")
                    .worldRestart()
                    .define("maxConcurrentSends", 2);

            autoconfigSendDistance = builder
                    .comment("Whether to use the client's view distance for the chunk send distance of the server. This will exclusively change the radius of chunks sent to the client and will not affect server-side chunk loading or ticking.")
                    .worldRestart()
                    .define("autoconfigSendDistance", true);

            targetPlayerChunkSendRate = builder
                    .comment("The maximum number of chunks sent to an individual player within one second.")
                    .worldRestart()
                    .define("targetPlayerChunkSendRate", 100.0);

            globalMaxChunkSendRate = builder
                    .comment("The maximum number of chunks loaded per second for the whole server. Takes priority over player-max-chunk-load-rate.")
                    .worldRestart()
                    .define("globalMaxChunkSendRate", (double)-1);

            enableFrustumPriority = builder
                    .comment("Whether to attempt to load chunks in front of the player before loading chunks to their sides or behind. Due to the client reacting poorly to receiving chunks out of order, this is disabled by default and not generally recommended for use.")
                    .worldRestart()
                    .define("enableFrustumPriority", false);

            globalMaxChunkLoadRate = builder
                    .comment("The maximum number of chunks loaded per second for the whole server. Takes priority over player-max-chunk-load-rate.")
                    .worldRestart()
                    .define("globalMaxChunkLoadRate", (double)-1);

            playerMaxConcurrentLoads = builder
                    .comment("The maximum number of chunk loads processed per player at one time.")
                    .worldRestart()
                    .define("playerMaxConcurrentLoads",20.0);

            globalMaxConcurrentLoads = builder
                    .comment("The maximum number of chunk loads processed for the whole server one time. This will override player-max-concurrent-loads if exceeded.")
                    .worldRestart()
                    .define("globalMaxConcurrentLoads",500.0);

            playerMaxChunkLoadRate = builder
                    .comment("The maximum number of chunks loaded per second per player.")
                    .worldRestart()
                    .define("playerMaxChunkLoadRate",(double)-1);

            builder.pop();

            builder.push("Velocity support");

            isVelocityEnabled = builder
                    .comment("Enable or disable velocity support")
                    .worldRestart()
                    .define("isVelocityEnabled", false);

            velocityForwardingToken = builder
                    .comment("The velocity modern forwarding token. Don't forget to disable online-mode in server.properties.")
                    .worldRestart()
                    .define("velocityForwardingToken", "");

            builder.pop();

            builder.push("Misc");
            maxJoinsPerTick = builder
                    .comment("Adjusts how many players are able to join in a single server tick.")
                    .worldRestart()
                    .define("maxJoinsPerTick", 5);

            preventMovingIntoUnloadedChunks = builder
                    .comment("Enable this to stop players from entering unloaded chunks.")
                    .worldRestart()
                    .define("preventMovingIntoUnloadedChunks", false);

            enableAsyncMobSpawning = builder
                    .comment("On servers with many entities, this can improve performance by up to 15%. paper's per-player-mob-spawns setting set to true for this to work.")
                    .worldRestart()
                    .define("enableAsyncMobSpawning", true);

            perPlayerMobSpawns = builder
                    .comment("Disable this on TNP limitless! Determines whether the mob limit is counted per player or for the entire server. Enabling this setting results in roughly the same number of mobs, but with a more even distribution that prevents one player from using the entire mob cap and provides a more single-player like experience.")
                    .worldRestart()
                    .define("perPlayerMobSpawns", true);

            loginTicks = builder
                    .comment("Login timeout in ticks")
                    .worldRestart()
                    .define("loginTicks", 12000);

            optimizeExplosions = builder
                    .worldRestart()
                    .define("optimizeExplosions", true);

            nettyThreads = builder
                    .worldRestart()
                    .define("nettyThreads", 4);

            builder.pop();

            builder.push("Auto save");
            autoSaveInterval = builder
                    .comment("Configures the world saving interval in ticks.")
                    .worldRestart()
                    .define("autoSaveInterval", 6000);

            maxAutoSaveChunksPerTick = builder
                    .comment("The maximum number of chunks the auto-save system will save in a single tick.")
                    .worldRestart()
                    .define("maxAutoSaveChunksPerTick", 24);

            builder.pop();

            builder.push("Chat");
            chatExecutorCoreSize = builder
                    .comment("Configures the world saving interval in ticks.")
                    .worldRestart()
                    .define("chatExecutorCoreSize", -1);

            chatExecutorMaxSize = builder
                    .comment("The maximum number of chunks the auto-save system will save in a single tick.")
                    .worldRestart()
                    .define("chatExecutorMaxSize", -1);

            builder.pop();
        }

        public void postConfig() {
            System.setProperty( "io.netty.eventLoopThreads", Integer.toString( nettyThreads.get() ) );
            MinecraftServer.LOGGER.info(  "Using {} threads for Netty based IO", nettyThreads.get() );

            int _chatExecutorMaxSize = (chatExecutorMaxSize.get() <= 0) ? Integer.MAX_VALUE : chatExecutorMaxSize.get(); // This is somewhat dumb, but, this is the default, do we cap this?;
            int _chatExecutorCoreSize = Math.max(chatExecutorCoreSize.get(), 0);

            if (_chatExecutorMaxSize < _chatExecutorCoreSize) {
                _chatExecutorMaxSize = _chatExecutorCoreSize;
            }

            java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) net.minecraft.server.MinecraftServer.getServer().chatExecutor;
            executor.setCorePoolSize(_chatExecutorCoreSize);
            executor.setMaximumPoolSize(_chatExecutorMaxSize);

        }

    }


    public static ForgeConfigSpec serverSpec;
    public static Server SERVER;
    static {
        final Pair<Server, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Server::new);
        serverSpec = specPair.getRight();
        SERVER = specPair.getLeft();
    }

    @SubscribeEvent
    public static void onLoad(final ModConfigEvent.Loading configEvent) {
        LogManager.getLogger().debug(FORGEMOD, "Loaded forge config file {}", configEvent.getConfig().getFileName());
    }

    @SubscribeEvent
    public static void onFileChange(final ModConfigEvent.Reloading configEvent) {
        LogManager.getLogger().debug(FORGEMOD, "Forge config just got changed on the file system!");
    }
}
