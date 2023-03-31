package io.papermc.paper.chunk.system;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import io.papermc.paper.chunk.system.scheduling.ChunkHolderManager;
import io.papermc.paper.util.CoordinateUtils;
import io.papermc.paper.util.IntegerUtil;
import io.papermc.paper.util.IntervalledCounter;
import io.papermc.paper.util.TickThread;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RegionizedPlayerChunkLoader {

    public static final TicketType<Long> REGION_PLAYER_TICKET = TicketType.create("region_player_ticket", Long::compareTo);

    public static final int MIN_VIEW_DISTANCE = 2;
    public static final int MAX_VIEW_DISTANCE = 32;

    public static final int TICK_TICKET_LEVEL = 31;
    public static final int GENERATED_TICKET_LEVEL = 33 + ChunkStatus.getDistance(ChunkStatus.FULL);
    public static final int LOADED_TICKET_LEVEL = 33 + ChunkStatus.getDistance(ChunkStatus.EMPTY);

    public static final record ViewDistances(
        int tickViewDistance,
        int loadViewDistance,
        int sendViewDistance
    ) {
        public ViewDistances setTickViewDistance(final int distance) {
            return new ViewDistances(distance, this.loadViewDistance, this.sendViewDistance);
        }

        public ViewDistances setLoadViewDistance(final int distance) {
            return new ViewDistances(this.tickViewDistance, distance, this.sendViewDistance);
        }


        public ViewDistances setSendViewDistance(final int distance) {
            return new ViewDistances(this.tickViewDistance, this.loadViewDistance, distance);
        }
    }


    public static int getAPITickViewDistance(final ServerPlayer player) {
        final ServerLevel level = (ServerLevel)player.level;
        final PlayerChunkLoaderData data = player.chunkLoader;
        if (data == null) {
            return level.playerChunkLoader.getAPITickDistance();
        }
        return data.lastTickDistance;
    }


    public static int getAPIViewDistance(final ServerPlayer player) {
        final ServerLevel level = (ServerLevel)player.level;
        final PlayerChunkLoaderData data = player.chunkLoader;
        if (data == null) {
            return level.playerChunkLoader.getAPIViewDistance();
        }
        // view distance = load distance + 1
        return data.lastLoadDistance - 1;
    }

    public static int getLoadViewDistance(final ServerPlayer player) {
        final ServerLevel level = (ServerLevel)player.level;
        final PlayerChunkLoaderData data = player.chunkLoader;
        if (data == null) {
            return level.playerChunkLoader.getAPIViewDistance();
        }
        // view distance = load distance + 1
        return data.lastLoadDistance - 1;
    }


    public static int getAPISendViewDistance(final ServerPlayer player) {
        final ServerLevel level = (ServerLevel)player.level;
        final PlayerChunkLoaderData data = player.chunkLoader;
        if (data == null) {
            return level.playerChunkLoader.getAPISendViewDistance();
        }
        return data.lastSendDistance;
    }

    private final ServerLevel world;

    public RegionizedPlayerChunkLoader(final ServerLevel world) {
        this.world = world;
    }

    public void addPlayer(final ServerPlayer player) {
        TickThread.ensureTickThread(player, "Cannot add player to player chunk loader async");
        if (!player.isRealPlayer) {
            return;
        }

        if (player.chunkLoader != null) {
            throw new IllegalStateException("Player is already added to player chunk loader");
        }

        final PlayerChunkLoaderData loader = new PlayerChunkLoaderData(this.world, player);

        player.chunkLoader = loader;
        loader.add();
    }

    public void updatePlayer(final ServerPlayer player) {
        final PlayerChunkLoaderData loader = player.chunkLoader;
        if (loader != null) {
            loader.update();
        }
    }

    public void removePlayer(final ServerPlayer player) {
        TickThread.ensureTickThread(player, "Cannot remove player from player chunk loader async");
        if (!player.isRealPlayer) {
            return;
        }

        final PlayerChunkLoaderData loader = player.chunkLoader;

        if (loader == null) {
            throw new IllegalStateException("Player is already removed from player chunk loader");
        }

        loader.remove();
        player.chunkLoader = null;
    }

    public void setSendDistance(final int distance) {
        this.world.setSendViewDistance(distance);
    }

    public void setLoadDistance(final int distance) {
        this.world.setLoadViewDistance(distance);
    }

    public void setTickDistance(final int distance) {
        this.world.setTickViewDistance(distance);
    }

    // Note: follow the player chunk loader so everything stays consistent...
    public int getAPITickDistance() {
        final ViewDistances distances = this.world.getViewDistances();
        final int tickViewDistance = PlayerChunkLoaderData.getTickDistance(-1, distances.tickViewDistance);
        return tickViewDistance;
    }

    public int getAPIViewDistance() {
        final ViewDistances distances = this.world.getViewDistances();
        final int tickViewDistance = PlayerChunkLoaderData.getTickDistance(-1, distances.tickViewDistance);
        final int loadDistance = PlayerChunkLoaderData.getLoadViewDistance(tickViewDistance, -1, distances.loadViewDistance);

        // loadDistance = api view distance + 1
        return loadDistance - 1;
    }

    public int getAPISendViewDistance() {
        final ViewDistances distances = this.world.getViewDistances();
        final int tickViewDistance = PlayerChunkLoaderData.getTickDistance(-1, distances.tickViewDistance);
        final int loadDistance = PlayerChunkLoaderData.getLoadViewDistance(tickViewDistance, -1, distances.loadViewDistance);
        final int sendViewDistance = PlayerChunkLoaderData.getSendViewDistance(
            loadDistance, -1, -1, distances.sendViewDistance
        );

        return sendViewDistance;
    }

    public boolean isChunkSent(final ServerPlayer player, final int chunkX, final int chunkZ, final boolean borderOnly) {
        return borderOnly ? this.isChunkSentBorderOnly(player, chunkX, chunkZ) : this.isChunkSent(player, chunkX, chunkZ);
    }

    public boolean isChunkSent(final ServerPlayer player, final int chunkX, final int chunkZ) {
        final PlayerChunkLoaderData loader = player.chunkLoader;
        if (loader == null) {
            return false;
        }

        return loader.sentChunks.contains(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    public boolean isChunkSentBorderOnly(final ServerPlayer player, final int chunkX, final int chunkZ) {
        final PlayerChunkLoaderData loader = player.chunkLoader;
        if (loader == null) {
            return false;
        }

        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (!loader.sentChunks.contains(CoordinateUtils.getChunkKey(dx + chunkX, dz + chunkZ))) {
                    return true;
                }
            }
        }

        return false;
    }

    public void tick() {
        TickThread.ensureTickThread("Cannot tick player chunk loader async");
        long currTime = System.nanoTime();
        for (final ServerPlayer player : this.world.getLocalPlayers()) { // Folia - region threding
            player.chunkLoader.update();
            player.chunkLoader.midTickUpdate(currTime);
        }
    }

    public boolean tickMidTick() { // Folia - region threading - report whether tickets were added
        final long time = System.nanoTime();
        boolean ret = false; // Folia - region threading - report whether tickets were added
        for (final ServerPlayer player : this.world.getLocalPlayers()) { // Folia - region threading
            ret |= player.chunkLoader.midTickUpdate(time);
        }
        return ret; // Folia - region threading - report whether tickets were added
    }

    private static long[] generateBFSOrder(final int radius) {
        final LongArrayList chunks = new LongArrayList();
        final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        final LongOpenHashSet seen = new LongOpenHashSet();

        seen.add(CoordinateUtils.getChunkKey(0, 0));
        queue.enqueue(CoordinateUtils.getChunkKey(0, 0));
        while (!queue.isEmpty()) {
            final long chunk = queue.dequeueLong();
            final int chunkX = CoordinateUtils.getChunkX(chunk);
            final int chunkZ = CoordinateUtils.getChunkZ(chunk);

            // important that the addition to the list is here, rather than when enqueueing neighbours
            // ensures the order is actually kept
            chunks.add(chunk);

            // -x
            final long n1 = CoordinateUtils.getChunkKey(chunkX - 1, chunkZ);
            // -z
            final long n2 = CoordinateUtils.getChunkKey(chunkX, chunkZ - 1);
            // +x
            final long n3 = CoordinateUtils.getChunkKey(chunkX + 1, chunkZ);
            // +z
            final long n4 = CoordinateUtils.getChunkKey(chunkX, chunkZ + 1);

            final long[] list = new long[] {n1, n2, n3, n4};

            for (final long neighbour : list) {
                final int neighbourX = CoordinateUtils.getChunkX(neighbour);
                final int neighbourZ = CoordinateUtils.getChunkZ(neighbour);
                if (Math.max(Math.abs(neighbourX), Math.abs(neighbourZ)) > radius) {
                    // don't enqueue out of range
                    continue;
                }
                if (!seen.add(neighbour)) {
                    continue;
                }
                queue.enqueue(neighbour);
            }
        }

        // to increase generation parallelism, we want to space the chunks out so that they are not nearby when generating
        // this also means we are minimising locality
        // but, we need to maintain sorted order by manhatten distance

        // first, build a map of manhatten distance -> chunks
        final List<LongArrayList> byDistance = new ArrayList<>();
        for (final LongIterator iterator = chunks.iterator(); iterator.hasNext();) {
            final long chunkKey = iterator.nextLong();

            final int chunkX = CoordinateUtils.getChunkX(chunkKey);
            final int chunkZ = CoordinateUtils.getChunkZ(chunkKey);

            final int dist = Math.abs(chunkX) + Math.abs(chunkZ);
            if (dist == byDistance.size()) {
                final LongArrayList list = new LongArrayList();
                list.add(chunkKey);
                byDistance.add(list);
                continue;
            }

            byDistance.get(dist).add(chunkKey);
        }

        // per distance we transform the chunk list so that each element is maximally spaced out from each other
        for (int i = 0, len = byDistance.size(); i < len; ++i) {
            final LongArrayList notAdded = byDistance.get(i).clone();
            final LongArrayList added = new LongArrayList();

            while (!notAdded.isEmpty()) {
                if (added.isEmpty()) {
                    added.add(notAdded.removeLong(notAdded.size() - 1));
                    continue;
                }

                long maxChunk = -1L;
                int maxDist = 0;

                // select the chunk from the not yet added set that has the largest minimum distance from
                // the current set of added chunks

                for (final LongIterator iterator = notAdded.iterator(); iterator.hasNext();) {
                    final long chunkKey = iterator.nextLong();
                    final int chunkX = CoordinateUtils.getChunkX(chunkKey);
                    final int chunkZ = CoordinateUtils.getChunkZ(chunkKey);

                    int minDist = Integer.MAX_VALUE;

                    for (final LongIterator iterator2 = added.iterator(); iterator2.hasNext();) {
                        final long addedKey = iterator2.nextLong();
                        final int addedX = CoordinateUtils.getChunkX(addedKey);
                        final int addedZ = CoordinateUtils.getChunkZ(addedKey);

                        // here we use square distance because chunk generation uses neighbours in a square radius
                        final int dist = Math.max(Math.abs(addedX - chunkX), Math.abs(addedZ - chunkZ));

                        if (dist < minDist) {
                            minDist = dist;
                        }
                    }

                    if (minDist > maxDist) {
                        maxDist = minDist;
                        maxChunk = chunkKey;
                    }
                }

                // move the selected chunk from the not added set to the added set

                if (!notAdded.rem(maxChunk)) {
                    throw new IllegalStateException();
                }

                added.add(maxChunk);
            }

            byDistance.set(i, added);
        }

        // now, rebuild the list so that it still maintains manhatten distance order
        final LongArrayList ret = new LongArrayList(chunks.size());

        for (final LongArrayList dist : byDistance) {
            ret.addAll(dist);
        }

        return ret.toLongArray();
    }

    public static final class PlayerChunkLoaderData {

        private static final AtomicLong ID_GENERATOR = new AtomicLong();
        private final long id = ID_GENERATOR.incrementAndGet();
        private final Long idBoxed = Long.valueOf(this.id);

        // expected that this list returns for a given radius, the set of chunks ordered
        // by manhattan distance
        private static final long[][] SEARCH_RADIUS_ITERATION_LIST = new long[65][];
        static {
            for (int i = 0; i < SEARCH_RADIUS_ITERATION_LIST.length; ++i) {
                // a BFS around -x, -z, +x, +z will give increasing manhatten distance
                SEARCH_RADIUS_ITERATION_LIST[i] = generateBFSOrder(i);
            }
        }

        private final ServerPlayer player;
        private final ServerLevel world;

        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;

        private int lastSendDistance = Integer.MIN_VALUE;
        private int lastLoadDistance = Integer.MIN_VALUE;
        private int lastTickDistance = Integer.MIN_VALUE;

        private int lastSentChunkCenterX = Integer.MIN_VALUE;
        private int lastSentChunkCenterZ = Integer.MIN_VALUE;

        private int lastSentChunkRadius = Integer.MIN_VALUE;
        private int lastSentSimulationDistance = Integer.MIN_VALUE;

        private boolean canGenerateChunks = true;

        private final ArrayDeque<ChunkHolderManager.TicketOperation<?, ?>> delayedTicketOps = new ArrayDeque<>();
        private final LongOpenHashSet sentChunks = new LongOpenHashSet();

        private static final byte CHUNK_TICKET_STAGE_NONE           = 0;
        private static final byte CHUNK_TICKET_STAGE_LOADING        = 1;
        private static final byte CHUNK_TICKET_STAGE_LOADED         = 2;
        private static final byte CHUNK_TICKET_STAGE_GENERATING     = 3;
        private static final byte CHUNK_TICKET_STAGE_GENERATED      = 4;
        private static final byte CHUNK_TICKET_STAGE_TICK           = 5;
        private static final int[] TICKET_STAGE_TO_LEVEL = new int[] {
            ChunkHolderManager.MAX_TICKET_LEVEL + 1,
            LOADED_TICKET_LEVEL,
            LOADED_TICKET_LEVEL,
            GENERATED_TICKET_LEVEL,
            GENERATED_TICKET_LEVEL,
            TICK_TICKET_LEVEL
        };
        private final Long2ByteOpenHashMap chunkTicketStage = new Long2ByteOpenHashMap();
        {
            this.chunkTicketStage.defaultReturnValue(CHUNK_TICKET_STAGE_NONE);
        }

        // rate limiting
        private final MultiIntervalledCounter chunkSendCounter = new MultiIntervalledCounter(
            TimeUnit.MILLISECONDS.toNanos(50L), TimeUnit.MILLISECONDS.toNanos(250L), TimeUnit.SECONDS.toNanos(1L)
        );
        private final MultiIntervalledCounter chunkLoadTicketCounter = new MultiIntervalledCounter(
            TimeUnit.MILLISECONDS.toNanos(50L), TimeUnit.MILLISECONDS.toNanos(250L), TimeUnit.SECONDS.toNanos(1L)
        );
        private final MultiIntervalledCounter chunkGenerateTicketCounter = new MultiIntervalledCounter(
            TimeUnit.MILLISECONDS.toNanos(50L), TimeUnit.MILLISECONDS.toNanos(250L), TimeUnit.SECONDS.toNanos(1L)
        );

        // queues
        private final LongComparator CLOSEST_MANHATTAN_DIST = (final long c1, final long c2) -> {
            final int c1x = CoordinateUtils.getChunkX(c1);
            final int c1z = CoordinateUtils.getChunkZ(c1);

            final int c2x = CoordinateUtils.getChunkX(c2);
            final int c2z = CoordinateUtils.getChunkZ(c2);

            final int centerX = PlayerChunkLoaderData.this.lastChunkX;
            final int centerZ = PlayerChunkLoaderData.this.lastChunkZ;

            return Integer.compare(
                Math.abs(c1x - centerX) + Math.abs(c1z - centerZ),
                Math.abs(c2x - centerX) + Math.abs(c2z - centerZ)
            );
        };
        private final LongHeapPriorityQueue sendQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);
        private final LongHeapPriorityQueue tickingQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);
        private final LongHeapPriorityQueue generatingQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);
        private final LongHeapPriorityQueue genQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);
        private final LongHeapPriorityQueue loadingQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);
        private final LongHeapPriorityQueue loadQueue = new LongHeapPriorityQueue(CLOSEST_MANHATTAN_DIST);

        public PlayerChunkLoaderData(final ServerLevel world, final ServerPlayer player) {
            this.world = world;
            this.player = player;
        }

        private boolean flushDelayedTicketOps() { // Folia - region threading - report whether tickets were added
            if (this.delayedTicketOps.isEmpty()) {
                return false; // Folia - region threading - report whether tickets were added
            }
            this.world.chunkTaskScheduler.chunkHolderManager.pushDelayedTicketUpdates(this.delayedTicketOps);
            this.delayedTicketOps.clear();
            return this.world.chunkTaskScheduler.chunkHolderManager.tryDrainTicketUpdates() == Boolean.TRUE; // Folia - region threading - report whether tickets were added
        }

        private void pushDelayedTicketOp(final ChunkHolderManager.TicketOperation<?, ?> op) {
            this.delayedTicketOps.addLast(op);
        }

        private void sendChunk(final int chunkX, final int chunkZ) {
            if (this.sentChunks.add(CoordinateUtils.getChunkKey(chunkX, chunkZ))) {
                this.world.getChunkSource().chunkMap.updateChunkTracking(this.player,
                    new ChunkPos(chunkX, chunkZ), new MutableObject<>(), false, true); // unloaded, loaded
                return;
            }
            throw new IllegalStateException();
        }

        private void sendUnloadChunk(final int chunkX, final int chunkZ) {
            if (!this.sentChunks.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ))) {
                return;
            }
            this.sendUnloadChunkRaw(chunkX, chunkZ);
        }

        private void sendUnloadChunkRaw(final int chunkX, final int chunkZ) {
            this.player.getLevel().getChunkSource().chunkMap.updateChunkTracking(this.player,
                new ChunkPos(chunkX, chunkZ), null, true, false); // unloaded, loaded
        }

        private final SingleUserAreaMap<PlayerChunkLoaderData> broadcastMap = new SingleUserAreaMap<>(this) {
            @Override
            protected void addCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                // do nothing, we only care about remove
            }

            @Override
            protected void removeCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                parameter.sendUnloadChunk(chunkX, chunkZ);
            }
        };
        private final SingleUserAreaMap<PlayerChunkLoaderData> loadTicketCleanup = new SingleUserAreaMap<>(this) {
            @Override
            protected void addCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                // do nothing, we only care about remove
            }

            @Override
            protected void removeCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                final long chunk = CoordinateUtils.getChunkKey(chunkX, chunkZ);
                final byte ticketStage = parameter.chunkTicketStage.remove(chunk);
                final int level = TICKET_STAGE_TO_LEVEL[ticketStage];
                if (level > ChunkHolderManager.MAX_TICKET_LEVEL) {
                    return;
                }

                parameter.pushDelayedTicketOp(ChunkHolderManager.TicketOperation.addAndRemove(
                    chunk,
                    TicketType.UNKNOWN, level, new ChunkPos(chunkX, chunkZ),
                    REGION_PLAYER_TICKET, level, parameter.idBoxed
                ));
            }
        };
        private final SingleUserAreaMap<PlayerChunkLoaderData> tickMap = new SingleUserAreaMap<>(this) {
            @Override
            protected void addCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                // do nothing, we will detect ticking chunks when we try to load them
            }

            @Override
            protected void removeCallback(final PlayerChunkLoaderData parameter, final int chunkX, final int chunkZ) {
                final long chunk = CoordinateUtils.getChunkKey(chunkX, chunkZ);
                // note: by the time this is called, the tick cleanup should have ran - so, if the chunk is at
                // the tick stage it was deemed in range for loading. Thus, we need to move it to generated
                if (!parameter.chunkTicketStage.replace(chunk, CHUNK_TICKET_STAGE_TICK, CHUNK_TICKET_STAGE_GENERATED)) {
                    return;
                }

                // Since we are possibly downgrading the ticket level, we add an unknown ticket so that
                // the level is kept until tick().
                parameter.pushDelayedTicketOp(ChunkHolderManager.TicketOperation.addAndRemove(
                    chunk,
                    TicketType.UNKNOWN, TICK_TICKET_LEVEL, new ChunkPos(chunkX, chunkZ),
                    REGION_PLAYER_TICKET, TICK_TICKET_LEVEL, parameter.idBoxed
                ));
                // keep chunk at new generated level
                parameter.pushDelayedTicketOp(ChunkHolderManager.TicketOperation.addOp(
                    chunk,
                    REGION_PLAYER_TICKET, GENERATED_TICKET_LEVEL, parameter.idBoxed
                ));
            }
        };

        private static boolean wantChunkLoaded(final int centerX, final int centerZ, final int chunkX, final int chunkZ,
                                               final int sendRadius) {
            // expect sendRadius to be = 1 + target viewable radius
            return ChunkMap.isChunkInRange(chunkX, chunkZ, centerX, centerZ, sendRadius);
        }

        private static int getClientViewDistance(final ServerPlayer player) {
            final Integer vd = player.clientViewDistance;
            return vd == null ? -1 : Math.max(0, vd.intValue());
        }

        private static int getTickDistance(final int playerTickViewDistance, final int worldTickViewDistance) {
            return playerTickViewDistance < 0 ? worldTickViewDistance : playerTickViewDistance;
        }

        private static int getLoadViewDistance(final int tickViewDistance, final int playerLoadViewDistance,
                                               final int worldLoadViewDistance) {
            return Math.max(tickViewDistance + 1, playerLoadViewDistance < 0 ? worldLoadViewDistance : playerLoadViewDistance);
        }

        private static int getSendViewDistance(final int loadViewDistance, final int clientViewDistance,
                                               final int playerSendViewDistance, final int worldSendViewDistance) {
            return Math.min(
                loadViewDistance,
                playerSendViewDistance < 0 ? (!GlobalConfiguration.get().chunkLoadingAdvanced.autoConfigSendDistance || clientViewDistance < 0 ? (worldSendViewDistance < 0 ? loadViewDistance : worldSendViewDistance) : clientViewDistance + 1) : playerSendViewDistance
            );
        }

        private Packet<?> updateClientChunkRadius(final int radius) {
            this.lastSentChunkRadius = radius;
            return new ClientboundSetChunkCacheRadiusPacket(radius);
        }

        private Packet<?> updateClientSimulationDistance(final int distance) {
            this.lastSentSimulationDistance = distance;
            return new ClientboundSetSimulationDistancePacket(distance);
        }

        private Packet<?> updateClientChunkCenter(final int chunkX, final int chunkZ) {
            this.lastSentChunkCenterX = chunkX;
            this.lastSentChunkCenterZ = chunkZ;
            return new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ);
        }

        private boolean canPlayerGenerateChunks() {
            return !this.player.isSpectator() || this.world.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
        }

        private int getMaxChunkLoads() {
            final int radiusChunks = (2 * this.lastLoadDistance + 1) * (2 * this.lastLoadDistance + 1);
            int configLimit = GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkLoads;
            if (configLimit == 0) {
                // by default, only allow 1/10th of the chunks in the view distance to be concurrently active
                configLimit = Math.max(5, radiusChunks / 10);
            } else if (configLimit < 0) {
                configLimit = Integer.MAX_VALUE;
            } // else: use the value configured
            configLimit = configLimit - this.loadingQueue.size();

            int rateLimit;
            double configRate = GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkLoadRate;
            if (configRate < 0.0 || configRate > (1000.0 * (double)radiusChunks)) {
                // getMaxCountBeforeViolation may not work with large config rates, so by checking against the load count we ensure
                // there are no issues with the cast to integer
                rateLimit = Integer.MAX_VALUE;
            } else {
                rateLimit = (int)this.chunkLoadTicketCounter.getMaxCountBeforeViolation(configRate);
            }

            return Math.min(configLimit, rateLimit);
        }

        private int getMaxChunkGenerates() {
            final int radiusChunks = (2 * this.lastLoadDistance + 1) * (2 * this.lastLoadDistance + 1);
            int configLimit = GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates;
            if (configLimit == 0) {
                // by default, only allow 1/10th of the chunks in the view distance to be concurrently active
                configLimit = Math.max(5, radiusChunks / 10);
            } else if (configLimit < 0) {
                configLimit = Integer.MAX_VALUE;
            } // else: use the value configured
            configLimit = configLimit - this.generatingQueue.size();

            int rateLimit;
            double configRate = GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkGenerateRate;
            if (configRate < 0.0 || configRate > (1000.0 * (double)radiusChunks)) {
                // getMaxCountBeforeViolation may not work with large config rates, so by checking against the load count we ensure
                // there are no issues with the cast to integer
                rateLimit = Integer.MAX_VALUE;
            } else {
                rateLimit = (int)this.chunkGenerateTicketCounter.getMaxCountBeforeViolation(configRate);
            }

            return Math.min(configLimit, rateLimit);
        }

        private int getMaxChunkSends() {
            final int radiusChunks = (2 * this.lastSendDistance + 1) * (2 * this.lastSendDistance + 1);

            int rateLimit;
            double configRate = GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate;
            if (configRate < 0.0 || configRate > (1000.0 * (double)radiusChunks)) {
                // getMaxCountBeforeViolation may not work with large config rates, so by checking against the load count we ensure
                // there are no issues with the cast to integer
                rateLimit = Integer.MAX_VALUE;
            } else {
                rateLimit = (int)this.chunkSendCounter.getMaxCountBeforeViolation(configRate);
            }

            return rateLimit;
        }

        private boolean wantChunkSent(final int chunkX, final int chunkZ) {
            final int dx = this.lastChunkX - chunkX;
            final int dz = this.lastChunkZ - chunkZ;
            return Math.max(Math.abs(dx), Math.abs(dz)) <= this.lastSendDistance && wantChunkLoaded(
                this.lastChunkX, this.lastChunkZ, chunkX, chunkZ, this.lastSendDistance
            );
        }

        private boolean wantChunkTicked(final int chunkX, final int chunkZ) {
            final int dx = this.lastChunkX - chunkX;
            final int dz = this.lastChunkZ - chunkZ;
            return Math.max(Math.abs(dx), Math.abs(dz)) <= this.lastTickDistance;
        }

        boolean midTickUpdate(final long time) { // Folia - region threading - report whether tickets were added
            TickThread.ensureTickThread(this.player, "Cannot tick player chunk loader async");
            // update rate limits
            this.chunkSendCounter.update(time);
            this.chunkGenerateTicketCounter.update(time);
            this.chunkLoadTicketCounter.update(time);

            // try to progress chunk loads
            while (!this.loadingQueue.isEmpty()) {
                final long pendingLoadChunk = this.loadingQueue.firstLong();
                final int pendingChunkX = CoordinateUtils.getChunkX(pendingLoadChunk);
                final int pendingChunkZ = CoordinateUtils.getChunkZ(pendingLoadChunk);
                final ChunkAccess pending = this.world.chunkSource.getChunkAtImmediately(pendingChunkX, pendingChunkZ);
                if (pending == null) {
                    // nothing to do here
                    break;
                }
                // chunk has loaded, so we can take it out of the queue
                this.loadingQueue.dequeueLong();

                // try to move to generate queue
                final byte prev = this.chunkTicketStage.put(pendingLoadChunk, CHUNK_TICKET_STAGE_LOADED);
                if (prev != CHUNK_TICKET_STAGE_LOADING) {
                    throw new IllegalStateException("Previous state should be " + CHUNK_TICKET_STAGE_LOADING + ", not " + prev);
                }

                if (this.canGenerateChunks || this.isLoadedChunkGeneratable(pending)) {
                    this.genQueue.enqueue(pendingLoadChunk);
                } // else: don't want to generate, so just leave it loaded
            }

            // try to push more chunk loads
            int loadSlots;
            while ((loadSlots = Math.min(this.getMaxChunkLoads(), this.loadQueue.size())) > 0) {
                final LongArrayList chunks = new LongArrayList(loadSlots);
                int actualLoadsQueued = 0;
                for (int i = 0; i < loadSlots; ++i) {
                    final long chunk = this.loadQueue.dequeueLong();
                    final byte prev = this.chunkTicketStage.put(chunk, CHUNK_TICKET_STAGE_LOADING);
                    if (prev != CHUNK_TICKET_STAGE_NONE) {
                        throw new IllegalStateException("Previous state should be " + CHUNK_TICKET_STAGE_NONE + ", not " + prev);
                    }
                    this.pushDelayedTicketOp(
                        ChunkHolderManager.TicketOperation.addOp(
                            chunk,
                            REGION_PLAYER_TICKET, LOADED_TICKET_LEVEL, this.idBoxed
                        )
                    );
                    chunks.add(chunk);
                    this.loadingQueue.enqueue(chunk);

                    if (this.world.chunkSource.getChunkAtImmediately(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk)) == null) {
                        // this is a good enough approximation for counting, but NOT for actual state management
                        ++actualLoadsQueued;
                    }
                }
                if (actualLoadsQueued > 0) {
                    this.chunkLoadTicketCounter.addTime(time, (long)actualLoadsQueued);
                }

                // here we need to flush tickets, as scheduleChunkLoad requires tickets to be propagated with addTicket = false
                this.flushDelayedTicketOps();
                // we only need to call scheduleChunkLoad because the loaded ticket level is not enough to start the chunk
                // load - only generate ticket levels start anything, but they start generation...
                // propagate levels
                // Note: this CAN call plugin logic, so it is VITAL that our bookkeeping logic is completely done by the time this is invoked
                this.world.chunkTaskScheduler.chunkHolderManager.processTicketUpdates();

                for (int i = 0; i < loadSlots; ++i) {
                    final long queuedLoadChunk = chunks.getLong(i);
                    final int queuedChunkX = CoordinateUtils.getChunkX(queuedLoadChunk);
                    final int queuedChunkZ = CoordinateUtils.getChunkZ(queuedLoadChunk);
                    this.world.chunkTaskScheduler.scheduleChunkLoad(
                        queuedChunkX, queuedChunkZ, ChunkStatus.EMPTY, false, PrioritisedExecutor.Priority.NORMAL, null
                    );
                }
            }

            // try to progress chunk generations
            while (!this.generatingQueue.isEmpty()) {
                final long pendingGenChunk = this.generatingQueue.firstLong();
                final int pendingChunkX = CoordinateUtils.getChunkX(pendingGenChunk);
                final int pendingChunkZ = CoordinateUtils.getChunkZ(pendingGenChunk);
                final LevelChunk pending = this.world.chunkSource.getChunkAtIfLoadedMainThreadNoCache(pendingChunkX, pendingChunkZ);
                if (pending == null) {
                    // nothing to do here
                    break;
                }

                // chunk has generated, so we can take it out of queue
                this.generatingQueue.dequeueLong();

                final byte prev = this.chunkTicketStage.put(pendingGenChunk, CHUNK_TICKET_STAGE_GENERATED);
                if (prev != CHUNK_TICKET_STAGE_GENERATING) {
                    throw new IllegalStateException("Previous state should be " + CHUNK_TICKET_STAGE_GENERATING + ", not " + prev);
                }

                // try to move to send queue
                if (this.wantChunkSent(pendingChunkX, pendingChunkZ)) {
                    this.sendQueue.enqueue(pendingGenChunk);
                }
                // try to move to tick queue
                if (this.wantChunkTicked(pendingChunkX, pendingChunkZ)) {
                    this.tickingQueue.enqueue(pendingGenChunk);
                }
            }

            // try to push more chunk generations
            int genSlots;
            while ((genSlots = Math.min(this.getMaxChunkGenerates(), this.genQueue.size())) > 0) {
                int actualGenerationsQueued = 0;
                for (int i = 0; i < genSlots; ++i) {
                    final long chunk = this.genQueue.dequeueLong();
                    final byte prev = this.chunkTicketStage.put(chunk, CHUNK_TICKET_STAGE_GENERATING);
                    if (prev != CHUNK_TICKET_STAGE_LOADED) {
                        throw new IllegalStateException("Previous state should be " + CHUNK_TICKET_STAGE_LOADED + ", not " + prev);
                    }
                    this.pushDelayedTicketOp(
                        ChunkHolderManager.TicketOperation.addAndRemove(
                            chunk,
                            REGION_PLAYER_TICKET, GENERATED_TICKET_LEVEL, this.idBoxed,
                            REGION_PLAYER_TICKET, LOADED_TICKET_LEVEL, this.idBoxed
                        )
                    );
                    this.generatingQueue.enqueue(chunk);
                    final ChunkAccess existingChunk = this.world.chunkSource.getChunkAtImmediately(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk));
                    if (existingChunk == null || !existingChunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                        // this is a good enough approximation for counting, but NOT for actual state management
                        ++actualGenerationsQueued;
                    }
                }
                if (actualGenerationsQueued > 0) {
                    this.chunkGenerateTicketCounter.addTime(time, (long)actualGenerationsQueued);
                }
            }

            // try to pull ticking chunks
            tick_check_outer:
            while (!this.tickingQueue.isEmpty()) {
                final long pendingTicking = this.tickingQueue.firstLong();
                final int pendingChunkX = CoordinateUtils.getChunkX(pendingTicking);
                final int pendingChunkZ = CoordinateUtils.getChunkZ(pendingTicking);

                final int tickingReq = 2;
                for (int dz = -tickingReq; dz <= tickingReq; ++dz) {
                    for (int dx = -tickingReq; dx <= tickingReq; ++dx) {
                        if ((dx | dz) == 0) {
                            continue;
                        }
                        final long neighbour = CoordinateUtils.getChunkKey(dx + pendingChunkX, dz + pendingChunkZ);
                        final byte stage = this.chunkTicketStage.get(neighbour);
                        if (stage != CHUNK_TICKET_STAGE_GENERATED && stage != CHUNK_TICKET_STAGE_TICK) {
                            break tick_check_outer;
                        }
                    }
                }
                // only gets here if all neighbours were marked as generated or ticking themselves
                this.tickingQueue.dequeueLong();
                this.pushDelayedTicketOp(
                    ChunkHolderManager.TicketOperation.addAndRemove(
                        pendingTicking,
                        REGION_PLAYER_TICKET, TICK_TICKET_LEVEL, this.idBoxed,
                        REGION_PLAYER_TICKET, GENERATED_TICKET_LEVEL, this.idBoxed
                    )
                );
                // there is no queue to add after ticking
                final byte prev = this.chunkTicketStage.put(pendingTicking, CHUNK_TICKET_STAGE_TICK);
                if (prev != CHUNK_TICKET_STAGE_GENERATED) {
                    throw new IllegalStateException("Previous state should be " + CHUNK_TICKET_STAGE_GENERATED + ", not " + prev);
                }
            }

            // try to pull sending chunks
            final int maxSends = this.getMaxChunkSends();
            final int sendSlots = Math.min(maxSends, this.sendQueue.size());
            for (int i = 0; i < sendSlots; ++i) {
                final long pendingSend = this.sendQueue.firstLong();
                final int pendingSendX = CoordinateUtils.getChunkX(pendingSend);
                final int pendingSendZ = CoordinateUtils.getChunkZ(pendingSend);
                final LevelChunk chunk = this.world.chunkSource.getChunkAtIfLoadedMainThreadNoCache(pendingSendX, pendingSendZ);
                if (!chunk.areNeighboursLoaded(1)) {
                    // nothing to do
                    break;
                }
                this.sendQueue.dequeueLong();

                this.sendChunk(pendingSendX, pendingSendZ);
            }
            if (sendSlots > 0) {
                this.chunkSendCounter.addTime(time, sendSlots);
            }

            return this.flushDelayedTicketOps(); // Folia - region threading - report whether tickets were added
            // we assume propagate ticket levels happens after this call
        }

        void add() {
            final ViewDistances playerDistances = this.player.getViewDistances();
            final ViewDistances worldDistances = this.world.getViewDistances();
            final int chunkX = this.player.chunkPosition().x;
            final int chunkZ = this.player.chunkPosition().z;

            final int tickViewDistance = getTickDistance(playerDistances.tickViewDistance, worldDistances.tickViewDistance);
            // load view cannot be less-than tick view + 1
            final int loadViewDistance = getLoadViewDistance(tickViewDistance, playerDistances.loadViewDistance, worldDistances.loadViewDistance);
            // send view cannot be greater-than load view
            final int clientViewDistance = getClientViewDistance(this.player);
            final int sendViewDistance = getSendViewDistance(loadViewDistance, clientViewDistance, playerDistances.sendViewDistance, worldDistances.sendViewDistance);

            // send view distances
            this.player.connection.send(this.updateClientChunkRadius(sendViewDistance));
            this.player.connection.send(this.updateClientSimulationDistance(tickViewDistance));

            // add to distance maps
            this.broadcastMap.add(chunkX, chunkZ, sendViewDistance);
            this.loadTicketCleanup.add(chunkX, chunkZ, loadViewDistance + 1);
            this.tickMap.add(chunkX, chunkZ, tickViewDistance);

            // update chunk center
            this.player.connection.send(this.updateClientChunkCenter(chunkX, chunkZ));

            // now we can update
            this.update();
        }

        private boolean isLoadedChunkGeneratable(final int chunkX, final int chunkZ) {
            return this.isLoadedChunkGeneratable(this.world.chunkSource.getChunkAtImmediately(chunkX, chunkZ));
        }

        private boolean isLoadedChunkGeneratable(final ChunkAccess chunkAccess) {
            final BelowZeroRetrogen belowZeroRetrogen;
            return chunkAccess != null && (
                chunkAccess.getStatus() == ChunkStatus.FULL ||
                    ((belowZeroRetrogen = chunkAccess.getBelowZeroRetrogen()) != null && belowZeroRetrogen.targetStatus().isOrAfter(ChunkStatus.FULL))
            );
        }

        void update() {
            final ViewDistances playerDistances = this.player.getViewDistances();
            final ViewDistances worldDistances = this.world.getViewDistances();

            final int tickViewDistance = getTickDistance(playerDistances.tickViewDistance, worldDistances.tickViewDistance);
            // load view cannot be less-than tick view + 1
            final int loadViewDistance = getLoadViewDistance(tickViewDistance, playerDistances.loadViewDistance, worldDistances.loadViewDistance);
            // send view cannot be greater-than load view
            final int clientViewDistance = getClientViewDistance(this.player);
            final int sendViewDistance = getSendViewDistance(loadViewDistance, clientViewDistance, playerDistances.sendViewDistance, worldDistances.sendViewDistance);

            final ChunkPos playerPos = this.player.chunkPosition();
            final boolean canGenerateChunks = this.canPlayerGenerateChunks();
            final int currentChunkX = playerPos.x;
            final int currentChunkZ = playerPos.z;

            final int prevChunkX = this.lastChunkX;
            final int prevChunkZ = this.lastChunkZ;

            if (
                // has view distance stayed the same?
                sendViewDistance == this.lastSendDistance
                    && loadViewDistance == this.lastLoadDistance
                    && tickViewDistance == this.lastTickDistance

                    // has our chunk stayed the same?
                    && prevChunkX == currentChunkX
                    && prevChunkZ == currentChunkZ

                    // can we still generate chunks?
                    && this.canGenerateChunks == canGenerateChunks
            ) {
                // nothing we care about changed, so we're not re-calculating
                return;
            }

            // update distance maps
            this.broadcastMap.update(currentChunkX, currentChunkZ, sendViewDistance);
            this.loadTicketCleanup.update(currentChunkX, currentChunkZ, loadViewDistance + 1);
            this.tickMap.update(currentChunkX, currentChunkZ, tickViewDistance);
            if (sendViewDistance > loadViewDistance || tickViewDistance > (loadViewDistance - 1)) {
                throw new IllegalStateException();
            }

            // update VDs for client
            // this should be after the distance map updates, as they will send unload packets
            if (this.lastSentChunkRadius != sendViewDistance) {
                this.player.connection.send(this.updateClientChunkRadius(sendViewDistance));
            }
            if (this.lastSentSimulationDistance != tickViewDistance) {
                this.player.connection.send(this.updateClientSimulationDistance(tickViewDistance));
            }

            this.sendQueue.clear();
            this.tickingQueue.clear();
            this.generatingQueue.clear();
            this.genQueue.clear();
            this.loadingQueue.clear();
            this.loadQueue.clear();

            this.lastChunkX = currentChunkX;
            this.lastChunkZ = currentChunkZ;
            this.lastSendDistance = sendViewDistance;
            this.lastLoadDistance = loadViewDistance;
            this.lastTickDistance = tickViewDistance;
            this.canGenerateChunks = canGenerateChunks;

            // +1 since we need to load chunks +1 around the load view distance...
            final long[] toIterate = SEARCH_RADIUS_ITERATION_LIST[loadViewDistance + 1];
            // the iteration order is by increasing manhattan distance - so, we do NOT need to
            // sort anything in the queue!
            for (final long deltaChunk : toIterate) {
                final int dx = CoordinateUtils.getChunkX(deltaChunk);
                final int dz = CoordinateUtils.getChunkZ(deltaChunk);
                final int chunkX = dx + currentChunkX;
                final int chunkZ = dz + currentChunkZ;
                final long chunk = CoordinateUtils.getChunkKey(chunkX, chunkZ);
                final int squareDistance = Math.max(Math.abs(dx), Math.abs(dz));
                final int manhattanDistance = Math.abs(dx) + Math.abs(dz);

                // since chunk sending is not by radius alone, we need an extra check here to account for
                // everything <= sendDistance
                // Note: Vanilla may want to send chunks outside the send view distance, so we do need
                // the dist <= view check
                final boolean sendChunk = squareDistance <= sendViewDistance
                    && wantChunkLoaded(currentChunkX, currentChunkZ, chunkX, chunkZ, sendViewDistance);
                final boolean sentChunk = sendChunk ? this.sentChunks.contains(chunk) : this.sentChunks.remove(chunk);

                if (!sendChunk && sentChunk) {
                    // have sent the chunk, but don't want it anymore
                    // unload it now
                    this.sendUnloadChunkRaw(chunkX, chunkZ);
                }

                final byte stage = this.chunkTicketStage.get(chunk);
                switch (stage) {
                    case CHUNK_TICKET_STAGE_NONE: {
                        // we want the chunk to be at least loaded
                        this.loadQueue.enqueue(chunk);
                        break;
                    }
                    case CHUNK_TICKET_STAGE_LOADING: {
                        this.loadingQueue.enqueue(chunk);
                        break;
                    }
                    case CHUNK_TICKET_STAGE_LOADED: {
                        if (canGenerateChunks || this.isLoadedChunkGeneratable(chunkX, chunkZ)) {
                            this.genQueue.enqueue(chunk);
                        }
                        break;
                    }
                    case CHUNK_TICKET_STAGE_GENERATING: {
                        this.generatingQueue.enqueue(chunk);
                        break;
                    }
                    case CHUNK_TICKET_STAGE_GENERATED: {
                        if (sendChunk && !sentChunk) {
                            this.sendQueue.enqueue(chunk);
                        }
                        if (squareDistance <= tickViewDistance) {
                            this.tickingQueue.enqueue(chunk);
                        }
                        break;
                    }
                    case CHUNK_TICKET_STAGE_TICK: {
                        if (sendChunk && !sentChunk) {
                            this.sendQueue.enqueue(chunk);
                        }
                        break;
                    }
                    default: {
                        throw new IllegalStateException("Unknown stage: " + stage);
                    }
                }
            }

            // update the chunk center
            // this must be done last so that the client does not ignore any of our unload chunk packets above
            if (this.lastSentChunkCenterX != currentChunkX || this.lastSentChunkCenterZ != currentChunkZ) {
                this.player.connection.send(this.updateClientChunkCenter(currentChunkX, currentChunkZ));
            }

            this.flushDelayedTicketOps();
        }

        void remove() {
            // sends the chunk unload packets
            this.broadcastMap.remove();
            // cleans up loading/generating tickets
            this.loadTicketCleanup.remove();
            // cleans up ticking tickets
            this.tickMap.remove();

            // purge queues
            this.sendQueue.clear();
            this.tickingQueue.clear();
            this.generatingQueue.clear();
            this.genQueue.clear();
            this.loadingQueue.clear();
            this.loadQueue.clear();

            // flush ticket changes
            this.flushDelayedTicketOps();

            // now all tickets should be removed, which is all of our external state
        }
    }

    private static final class MultiIntervalledCounter {

        private final IntervalledCounter[] counters;

        public MultiIntervalledCounter(final long... intervals) {
            final IntervalledCounter[] counters = new IntervalledCounter[intervals.length];
            for (int i = 0; i < intervals.length; ++i) {
                counters[i] = new IntervalledCounter(intervals[i]);
            }
            this.counters = counters;
        }

        public long getMaxCountBeforeViolation(final double rate) {
            long count = Long.MAX_VALUE;
            for (final IntervalledCounter counter : this.counters) {
                final long sum = counter.getSum();
                final long interval = counter.getInterval();
                // rate = sum / interval
                // so, sum = rate*interval
                final long maxSum = (long)Math.ceil(rate * (1.0E-9 * (double)interval));
                final long diff = maxSum - sum;
                if (diff < count) {
                    count = diff;
                }
            }

            return Math.max(0L, count);
        }

        public void update(final long time) {
            for (final IntervalledCounter counter : this.counters) {
                counter.updateCurrentTime(time);
            }
        }

        public void updateAndAdd(final long count, final long time) {
            for (final IntervalledCounter counter : this.counters) {
                counter.updateAndAdd(count, time);
            }
        }

        public void addTime(final long time, final long count) {
            for (final IntervalledCounter counter : this.counters) {
                counter.addTime(time, count);
            }
        }

        public double getMaxRate() {
            double ret = 0.0;

            for (final IntervalledCounter counter : this.counters) {
                final double counterRate = counter.getRate();
                if (counterRate > ret) {
                    ret = counterRate;
                }
            }

            return ret;
        }
    }

    // TODO rebase into util patch
    public static abstract class SingleUserAreaMap<T> {

        private static final int NOT_SET = Integer.MIN_VALUE;

        private final T parameter;
        private int lastChunkX = NOT_SET;
        private int lastChunkZ = NOT_SET;
        private int distance = NOT_SET;

        public SingleUserAreaMap(final T parameter) {
            this.parameter = parameter;
        }

        /* math sign function except 0 returns 1 */
        protected static int sign(int val) {
            return 1 | (val >> (Integer.SIZE - 1));
        }

        protected abstract void addCallback(final T parameter, final int chunkX, final int chunkZ);

        protected abstract void removeCallback(final T parameter, final int chunkX, final int chunkZ);

        private void addToNew(final T parameter, final int chunkX, final int chunkZ, final int distance) {
            final int maxX = chunkX + distance;
            final int maxZ = chunkZ + distance;

            for (int cx = chunkX - distance; cx <= maxX; ++cx) {
                for (int cz = chunkZ - distance; cz <= maxZ; ++cz) {
                    this.addCallback(parameter, cx, cz);
                }
            }
        }

        private void removeFromOld(final T parameter, final int chunkX, final int chunkZ, final int distance) {
            final int maxX = chunkX + distance;
            final int maxZ = chunkZ + distance;

            for (int cx = chunkX - distance; cx <= maxX; ++cx) {
                for (int cz = chunkZ - distance; cz <= maxZ; ++cz) {
                    this.removeCallback(parameter, cx, cz);
                }
            }
        }

        public final boolean add(final int chunkX, final int chunkZ, final int distance) {
            if (distance < 0) {
                throw new IllegalArgumentException(Integer.toString(distance));
            }
            if (this.lastChunkX != NOT_SET) {
                return false;
            }
            this.lastChunkX = chunkX;
            this.lastChunkZ = chunkZ;
            this.distance = distance;

            this.addToNew(this.parameter, chunkX, chunkZ, distance);

            return true;
        }

        public final boolean update(final int toX, final int toZ, final int newViewDistance) {
            if (newViewDistance < 0) {
                throw new IllegalArgumentException(Integer.toString(newViewDistance));
            }
            final int fromX = this.lastChunkX;
            final int fromZ = this.lastChunkZ;
            final int oldViewDistance = this.distance;
            if (fromX == NOT_SET) {
                return false;
            }

            this.lastChunkX = toX;
            this.lastChunkZ = toZ;

            final T parameter = this.parameter;


            final int dx = toX - fromX;
            final int dz = toZ - fromZ;

            final int totalX = IntegerUtil.branchlessAbs(fromX - toX);
            final int totalZ = IntegerUtil.branchlessAbs(fromZ - toZ);

            if (Math.max(totalX, totalZ) > (2 * Math.max(newViewDistance, oldViewDistance))) {
                // teleported?
                this.removeFromOld(parameter, fromX, fromZ, oldViewDistance);
                this.addToNew(parameter, toX, toZ, newViewDistance);
                return true;
            }

            if (oldViewDistance != newViewDistance) {
                // remove loop

                final int oldMinX = fromX - oldViewDistance;
                final int oldMinZ = fromZ - oldViewDistance;
                final int oldMaxX = fromX + oldViewDistance;
                final int oldMaxZ = fromZ + oldViewDistance;
                for (int currX = oldMinX; currX <= oldMaxX; ++currX) {
                    for (int currZ = oldMinZ; currZ <= oldMaxZ; ++currZ) {

                        // only remove if we're outside the new view distance...
                        if (Math.max(IntegerUtil.branchlessAbs(currX - toX), IntegerUtil.branchlessAbs(currZ - toZ)) > newViewDistance) {
                            this.removeCallback(parameter, currX, currZ);
                        }
                    }
                }

                // add loop

                final int newMinX = toX - newViewDistance;
                final int newMinZ = toZ - newViewDistance;
                final int newMaxX = toX + newViewDistance;
                final int newMaxZ = toZ + newViewDistance;
                for (int currX = newMinX; currX <= newMaxX; ++currX) {
                    for (int currZ = newMinZ; currZ <= newMaxZ; ++currZ) {

                        // only add if we're outside the old view distance...
                        if (Math.max(IntegerUtil.branchlessAbs(currX - fromX), IntegerUtil.branchlessAbs(currZ - fromZ)) > oldViewDistance) {
                            this.addCallback(parameter, currX, currZ);
                        }
                    }
                }

                return true;
            }

            // x axis is width
            // z axis is height
            // right refers to the x axis of where we moved
            // top refers to the z axis of where we moved

            // same view distance

            // used for relative positioning
            final int up = sign(dz); // 1 if dz >= 0, -1 otherwise
            final int right = sign(dx); // 1 if dx >= 0, -1 otherwise

            // The area excluded by overlapping the two view distance squares creates four rectangles:
            // Two on the left, and two on the right. The ones on the left we consider the "removed" section
            // and on the right the "added" section.
            // https://i.imgur.com/MrnOBgI.png is a reference image. Note that the outside border is not actually
            // exclusive to the regions they surround.

            // 4 points of the rectangle
            int maxX; // exclusive
            int minX; // inclusive
            int maxZ; // exclusive
            int minZ; // inclusive

            if (dx != 0) {
                // handle right addition

                maxX = toX + (oldViewDistance * right) + right; // exclusive
                minX = fromX + (oldViewDistance * right) + right; // inclusive
                maxZ = fromZ + (oldViewDistance * up) + up; // exclusive
                minZ = toZ - (oldViewDistance * up); // inclusive

                for (int currX = minX; currX != maxX; currX += right) {
                    for (int currZ = minZ; currZ != maxZ; currZ += up) {
                        this.addCallback(parameter, currX, currZ);
                    }
                }
            }

            if (dz != 0) {
                // handle up addition

                maxX = toX + (oldViewDistance * right) + right; // exclusive
                minX = toX - (oldViewDistance * right); // inclusive
                maxZ = toZ + (oldViewDistance * up) + up; // exclusive
                minZ = fromZ + (oldViewDistance * up) + up; // inclusive

                for (int currX = minX; currX != maxX; currX += right) {
                    for (int currZ = minZ; currZ != maxZ; currZ += up) {
                        this.addCallback(parameter, currX, currZ);
                    }
                }
            }

            if (dx != 0) {
                // handle left removal

                maxX = toX - (oldViewDistance * right); // exclusive
                minX = fromX - (oldViewDistance * right); // inclusive
                maxZ = fromZ + (oldViewDistance * up) + up; // exclusive
                minZ = toZ - (oldViewDistance * up); // inclusive

                for (int currX = minX; currX != maxX; currX += right) {
                    for (int currZ = minZ; currZ != maxZ; currZ += up) {
                        this.removeCallback(parameter, currX, currZ);
                    }
                }
            }

            if (dz != 0) {
                // handle down removal

                maxX = fromX + (oldViewDistance * right) + right; // exclusive
                minX = fromX - (oldViewDistance * right); // inclusive
                maxZ = toZ - (oldViewDistance * up); // exclusive
                minZ = fromZ - (oldViewDistance * up); // inclusive

                for (int currX = minX; currX != maxX; currX += right) {
                    for (int currZ = minZ; currZ != maxZ; currZ += up) {
                        this.removeCallback(parameter, currX, currZ);
                    }
                }
            }

            return true;
        }

        public final boolean remove() {
            final int chunkX = this.lastChunkX;
            final int chunkZ = this.lastChunkZ;
            final int distance = this.distance;
            if (chunkX == NOT_SET) {
                return false;
            }

            this.lastChunkX = this.lastChunkZ = this.distance = NOT_SET;

            this.removeFromOld(this.parameter, chunkX, chunkZ, distance);

            return true;
        }
    }
}
