package io.papermc.paper.threadedregions;

import com.destroystokyo.paper.util.maplist.ReferenceList;
import com.destroystokyo.paper.util.misc.PlayerAreaMap;
import com.destroystokyo.paper.util.misc.PooledLinkedHashSets;
import com.mojang.logging.LogUtils;
import io.papermc.paper.chunk.system.scheduling.ChunkHolderManager;
import io.papermc.paper.util.CoordinateUtils;
import io.papermc.paper.util.TickThread;
import io.papermc.paper.util.maplist.IteratorSafeOrderedReferenceSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import org.bukkit.craftbukkit.util.UnsafeList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RegionizedWorldData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final RegionizedData.RegioniserCallback<RegionizedWorldData> REGION_CALLBACK = new RegionizedData.RegioniserCallback<>() {
        @Override
        public void merge(final RegionizedWorldData from, final RegionizedWorldData into, final long fromTickOffset) {
            // connections
            for (final Connection conn : from.connections) {
                into.connections.add(conn);
            }
            // time
            final long fromRedstoneTimeOffset = from.redstoneTime - into.redstoneTime;
            // entities
            for (final ServerPlayer player : from.localPlayers) {
                into.localPlayers.add(player);
            }
            for (final Entity entity : from.allEntities) {
                into.allEntities.add(entity);
                entity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
            }
            for (final Iterator<Entity> iterator = from.entityTickList.unsafeIterator(); iterator.hasNext();) {
                into.entityTickList.add(iterator.next());
            }
            for (final Iterator<Mob> iterator = from.navigatingMobs.unsafeIterator(); iterator.hasNext();) {
                into.navigatingMobs.add(iterator.next());
            }
            // block ticking
            into.blockEvents.addAll(from.blockEvents);
            // ticklists use game time
            from.blockLevelTicks.merge(into.blockLevelTicks, fromRedstoneTimeOffset);
            from.fluidLevelTicks.merge(into.fluidLevelTicks, fromRedstoneTimeOffset);

            // tile entity ticking
            for (final TickingBlockEntity tileEntityWrapped : from.pendingBlockEntityTickers) {
                into.pendingBlockEntityTickers.add(tileEntityWrapped);
                final BlockEntity tileEntity = tileEntityWrapped.getTileEntity();
                if (tileEntity != null) {
                    tileEntity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
                }
            }
            for (final TickingBlockEntity tileEntityWrapped : from.blockEntityTickers) {
                into.blockEntityTickers.add(tileEntityWrapped);
                final BlockEntity tileEntity = tileEntityWrapped.getTileEntity();
                if (tileEntity != null) {
                    tileEntity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
                }
            }

            // ticking chunks
            for (final Iterator<LevelChunk> iterator = from.entityTickingChunks.unsafeIterator(); iterator.hasNext();) {
                into.entityTickingChunks.add(iterator.next());
            }
            // redstone torches
            if (from.redstoneUpdateInfos != null && !from.redstoneUpdateInfos.isEmpty()) {
                if (into.redstoneUpdateInfos == null) {
                    into.redstoneUpdateInfos = new ArrayDeque<>();
                }
                for (final net.minecraft.world.level.block.RedstoneTorchBlock.Toggle info : from.redstoneUpdateInfos) {
                    info.offsetTime(fromRedstoneTimeOffset);
                    into.redstoneUpdateInfos.add(info);
                }
            }
            // light chunks being worked on
            into.chunksBeingWorkedOn.putAll(from.chunksBeingWorkedOn);
            // mob spawning
            into.catSpawnerNextTick = Math.max(from.catSpawnerNextTick, into.catSpawnerNextTick);
            into.patrolSpawnerNextTick = Math.max(from.patrolSpawnerNextTick, into.patrolSpawnerNextTick);
            into.phantomSpawnerNextTick = Math.max(from.phantomSpawnerNextTick, into.phantomSpawnerNextTick);
            if (from.wanderingTraderTickDelay != Integer.MIN_VALUE && into.wanderingTraderTickDelay != Integer.MIN_VALUE) {
                into.wanderingTraderTickDelay = Math.max(from.wanderingTraderTickDelay, into.wanderingTraderTickDelay);
                into.wanderingTraderSpawnDelay = Math.max(from.wanderingTraderSpawnDelay, into.wanderingTraderSpawnDelay);
                into.wanderingTraderSpawnChance = Math.max(from.wanderingTraderSpawnChance, into.wanderingTraderSpawnChance);
            }
        }

        @Override
        public void split(final RegionizedWorldData from, final int chunkToRegionShift,
                          final Long2ReferenceOpenHashMap<RegionizedWorldData> regionToData,
                          final ReferenceOpenHashSet<RegionizedWorldData> dataSet) {
            // connections
            for (final Connection conn : from.connections) {
                final ServerPlayer player = conn.getPlayer();
                final ChunkPos pos = player.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                regionToData.get(CoordinateUtils.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift))
                    .connections.add(conn);
            }
            // entities
            for (final ServerPlayer player : from.localPlayers) {
                final ChunkPos pos = player.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                regionToData.get(CoordinateUtils.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift))
                    .localPlayers.add(player);
            }
            for (final Entity entity : from.allEntities) {
                final ChunkPos pos = entity.chunkPosition();
                // Note: It is impossible for an entity in the world to _not_ be in an entity chunk, which means
                // the chunk holder must _exist_, and so the region section exists.
                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift));
                into.allEntities.add(entity);
                // Note: entityTickList is a subset of allEntities
                if (from.entityTickList.contains(entity)) {
                    into.entityTickList.add(entity);
                }
                // Note: navigatingMobs is a subset of allEntities
                if (entity instanceof Mob mob && from.navigatingMobs.contains(mob)) {
                    into.navigatingMobs.add(mob);
                }
            }
            // block ticking
            for (final BlockEventData blockEventData : from.blockEvents) {
                final BlockPos pos = blockEventData.pos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                // Unlike entities, the chunk holder is not guaranteed to exist for block events, because the block events
                // is just some list. So if it unloads, I guess it's just lost.
                if (into != null) {
                    into.blockEvents.add(blockEventData);
                }
            }

            final Long2ReferenceOpenHashMap<LevelTicks<Block>> levelTicksBlockRegionData = new Long2ReferenceOpenHashMap<>(regionToData.size(), 0.75f);
            final Long2ReferenceOpenHashMap<LevelTicks<Fluid>> levelTicksFluidRegionData = new Long2ReferenceOpenHashMap<>(regionToData.size(), 0.75f);

            for (final Iterator<Long2ReferenceMap.Entry<RegionizedWorldData>> iterator = regionToData.long2ReferenceEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ReferenceMap.Entry<RegionizedWorldData> entry = iterator.next();
                final long key = entry.getLongKey();
                final RegionizedWorldData worldData = entry.getValue();

                levelTicksBlockRegionData.put(key, worldData.blockLevelTicks);
                levelTicksFluidRegionData.put(key, worldData.fluidLevelTicks);
            }

            from.blockLevelTicks.split(chunkToRegionShift, levelTicksBlockRegionData);
            from.fluidLevelTicks.split(chunkToRegionShift, levelTicksFluidRegionData);

            // tile entity ticking
            for (final TickingBlockEntity tileEntity : from.pendingBlockEntityTickers) {
                final BlockPos pos = tileEntity.getPos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                if (into != null) {
                    into.pendingBlockEntityTickers.add(tileEntity);
                } // else: when a chunk unloads, it does not actually _remove_ the tile entity from the list, it just gets
                  //       marked as removed. So if there is no section, it's probably removed!
            }
            for (final TickingBlockEntity tileEntity : from.blockEntityTickers) {
                final BlockPos pos = tileEntity.getPos();
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;

                final RegionizedWorldData into = regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift));
                if (into != null) {
                    into.blockEntityTickers.add(tileEntity);
                } // else: when a chunk unloads, it does not actually _remove_ the tile entity from the list, it just gets
                  //       marked as removed. So if there is no section, it's probably removed!
            }
            // time
            for (final RegionizedWorldData regionizedWorldData : dataSet) {
                regionizedWorldData.redstoneTime = from.redstoneTime;
            }
            // ticking chunks
            for (final Iterator<LevelChunk> iterator = from.entityTickingChunks.unsafeIterator(); iterator.hasNext();) {
                final LevelChunk levelChunk = iterator.next();
                final ChunkPos pos = levelChunk.getPos();

                // Impossible for get() to return null, as the chunk is entity ticking - thus the chunk holder is loaded
                regionToData.get(CoordinateUtils.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift))
                    .entityTickingChunks.add(levelChunk);
            }
            // redstone torches
            if (from.redstoneUpdateInfos != null && !from.redstoneUpdateInfos.isEmpty()) {
                for (final net.minecraft.world.level.block.RedstoneTorchBlock.Toggle info : from.redstoneUpdateInfos) {
                    final BlockPos pos = info.pos;

                    final RegionizedWorldData worldData = regionToData.get(CoordinateUtils.getChunkKey((pos.getX() >> 4) >> chunkToRegionShift, (pos.getZ() >> 4) >> chunkToRegionShift));
                    if (worldData != null) {
                        if (worldData.redstoneUpdateInfos == null) {
                            worldData.redstoneUpdateInfos = new ArrayDeque<>();
                        }
                        worldData.redstoneUpdateInfos.add(info);
                    } // else: chunk unloaded
                }
            }
            // light chunks being worked on
            for (final Iterator<Long2IntOpenHashMap.Entry> iterator = from.chunksBeingWorkedOn.long2IntEntrySet().fastIterator(); iterator.hasNext();) {
                final Long2IntOpenHashMap.Entry entry = iterator.next();
                final long pos = entry.getLongKey();
                final int chunkX = CoordinateUtils.getChunkX(pos);
                final int chunkZ = CoordinateUtils.getChunkZ(pos);
                final int value = entry.getIntValue();

                // should never be null, as it is a reference counter for ticket
                regionToData.get(CoordinateUtils.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift)).chunksBeingWorkedOn.put(pos, value);
            }
            // mob spawning
            for (final RegionizedWorldData regionizedWorldData : dataSet) {
                regionizedWorldData.catSpawnerNextTick = from.catSpawnerNextTick;
                regionizedWorldData.patrolSpawnerNextTick = from.patrolSpawnerNextTick;
                regionizedWorldData.phantomSpawnerNextTick = from.phantomSpawnerNextTick;
                regionizedWorldData.wanderingTraderTickDelay = from.wanderingTraderTickDelay;
                regionizedWorldData.wanderingTraderSpawnChance = from.wanderingTraderSpawnChance;
                regionizedWorldData.wanderingTraderSpawnDelay = from.wanderingTraderSpawnDelay;
                regionizedWorldData.villageSiegeState = new VillageSiegeState(); // just re set it, as the spawn pos will be invalid
            }
        }
    };

    public final ServerLevel world;

    private RegionizedServer.WorldLevelData tickData;

    // connections
    public final List<Connection> connections = new ArrayList<>();

    // misc. fields
    private boolean isHandlingTick;

    public void setHandlingTick(final boolean to) {
        this.isHandlingTick = to;
    }

    public boolean isHandlingTick() {
        return this.isHandlingTick;
    }

    // entities
    private final List<ServerPlayer> localPlayers = new ArrayList<>();
    private final ReferenceList<Entity> allEntities = new ReferenceList<>();
    private final IteratorSafeOrderedReferenceSet<Entity> entityTickList = new IteratorSafeOrderedReferenceSet<>();
    private final IteratorSafeOrderedReferenceSet<Mob> navigatingMobs = new IteratorSafeOrderedReferenceSet<>();

    // block ticking
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private final LevelTicks<Block> blockLevelTicks;
    private final LevelTicks<Fluid> fluidLevelTicks;

    // tile entity ticking
    private final List<TickingBlockEntity> pendingBlockEntityTickers = new ArrayList<>();
    private final List<TickingBlockEntity> blockEntityTickers = new ArrayList<>();
    private boolean tickingBlockEntities;

    // time
    private long redstoneTime = 1L;

    public long getRedstoneGameTime() {
        return this.redstoneTime;
    }

    public void setRedstoneGameTime(final long to) {
        this.redstoneTime = to;
    }

    // ticking chunks
    private final IteratorSafeOrderedReferenceSet<LevelChunk> entityTickingChunks = new IteratorSafeOrderedReferenceSet<>();

    // Paper/CB api hook misc
    // don't bother to merge/split these, no point
    // From ServerLevel
    // Paper start - Optimize Hoppers
    public boolean skipPullModeEventFire = false;
    public boolean skipPushModeEventFire = false;
    // Paper end - Optimize Hoppers
    public long lastMidTickExecuteFailure;
    public long lastMidTickExecute;
    // From Level
    public boolean populating;
    public final NeighborUpdater neighborUpdater;
    public boolean preventPoiUpdated = false; // CraftBukkit - SPIGOT-5710
    public boolean captureBlockStates = false;
    public boolean captureTreeGeneration = false;
    //public final Map<BlockPos, CraftBlockState> capturedBlockStates = new java.util.LinkedHashMap<>(); // Paper
    public final Map<BlockPos, BlockEntity> capturedTileEntities = new java.util.LinkedHashMap<>(); // Paper
    public List<ItemEntity> captureDrops;
    // Paper start
    public int wakeupInactiveRemainingAnimals;
    public int wakeupInactiveRemainingFlying;
    public int wakeupInactiveRemainingMonsters;
    public int wakeupInactiveRemainingVillagers;
    // Paper end
    public final TempCollisionList<AABB> tempCollisionList = new TempCollisionList<>();
    public final TempCollisionList<Entity> tempEntitiesList = new TempCollisionList<>();
    public int currentPrimedTnt = 0; // Spigot
    @Nullable
    @VisibleForDebug
    public NaturalSpawner.SpawnState lastSpawnState;
    public boolean shouldSignal = true;

    // not transient
    public ArrayDeque<net.minecraft.world.level.block.RedstoneTorchBlock.Toggle> redstoneUpdateInfos;
    public final Long2IntOpenHashMap chunksBeingWorkedOn = new Long2IntOpenHashMap();

    public static final class TempCollisionList<T> {
        final UnsafeList<T> list = new UnsafeList<>(64);
        boolean inUse;

        public UnsafeList<T> get() {
            if (this.inUse) {
                return new UnsafeList<>(16);
            }
            this.inUse = true;
            return this.list;
        }

        public void ret(List<T> list) {
            if (list != this.list) {
                return;
            }

            ((UnsafeList)list).setSize(0);
            this.inUse = false;
        }

        public void reset() {
            this.list.completeReset();
        }
    }
    public void resetCollisionLists() {
        this.tempCollisionList.reset();
        this.tempEntitiesList.reset();
    }

    // Mob spawning
    private final PooledLinkedHashSets<ServerPlayer> pooledHashSets = new PooledLinkedHashSets<>();
    public final PlayerAreaMap mobSpawnMap = new PlayerAreaMap(this.pooledHashSets);
    public int catSpawnerNextTick = 0;
    public int patrolSpawnerNextTick = 0;
    public int phantomSpawnerNextTick = 0;
    public int wanderingTraderTickDelay = Integer.MIN_VALUE;
    public int wanderingTraderSpawnDelay;
    public int wanderingTraderSpawnChance;
    public VillageSiegeState villageSiegeState = new VillageSiegeState();

    public static final class VillageSiegeState {
        public boolean hasSetupSiege;
        public VillageSiege.State siegeState = VillageSiege.State.SIEGE_DONE;
        public int zombiesToSpawn;
        public int nextSpawnTime;
        public int spawnX;
        public int spawnY;
        public int spawnZ;
    }

    public RegionizedWorldData(final ServerLevel world) {
        this.world = world;
        this.blockLevelTicks = new LevelTicks<>(world::isPositionTickingWithEntitiesLoaded, world.getProfilerSupplier(), world, true);
        this.fluidLevelTicks = new LevelTicks<>(world::isPositionTickingWithEntitiesLoaded, world.getProfilerSupplier(), world, false);
        this.neighborUpdater = new CollectingNeighborUpdater(world, world.neighbourUpdateMax);

        // tasks may be drained before the region ticks, so we must set up the tick data early just in case
        this.updateTickData();
    }

    public void checkWorld(final Level against) {
        if (this.world != against) {
            throw new IllegalStateException("World mismatch: expected " + this.world.getWorld().getName() + " but got " + (against == null ? "null" : against.getWorld().getName()));
        }
    }

    public RegionizedServer.WorldLevelData getTickData() {
        return this.tickData;
    }

    public void updateTickData() {
        this.tickData = this.world.tickData;
    }

    // connections
    public void tickConnections() {
        final List<Connection> connections = new ArrayList<>(this.connections);
        Collections.shuffle(connections);
        for (final Connection conn : connections) {
            if (!conn.isConnected()) {
                conn.handleDisconnection();
                this.connections.remove(conn);
                // note: ALL connections HERE have a player
                final ServerPlayer player = conn.getPlayer();
                // now that the connection is removed, we can allow this region to die
                player.getLevel().chunkSource.removeTicketAtLevel(
                    ServerGamePacketListenerImpl.DISCONNECT_TICKET, player.connection.disconnectPos,
                    ChunkHolderManager.MAX_TICKET_LEVEL,
                    player.connection.disconnectTicketId
                );
                continue;
            }
            if (!this.connections.contains(conn)) {
                // removed by connection tick?
                continue;
            }

            try {
                conn.tick();
            } catch (final Exception exception) {
                if (conn.isMemoryConnection()) {
                    throw new ReportedException(CrashReport.forThrowable(exception, "Ticking memory connection"));
                }

                LOGGER.warn("Failed to handle packet for {}", io.papermc.paper.configuration.GlobalConfiguration.get().logging.logPlayerIpAddresses ? String.valueOf(conn.getRemoteAddress()) : "<ip address withheld>", exception); // Paper
                MutableComponent ichatmutablecomponent = Component.literal("Internal server error");

                conn.send(new ClientboundDisconnectPacket(ichatmutablecomponent), PacketSendListener.thenRun(() -> {
                    conn.disconnect(ichatmutablecomponent);
                }));
                conn.setReadOnly();
                continue;
            }
        }
    }

    // entities hooks
    public Iterable<Entity> getLocalEntities() {
        return this.allEntities;
    }

    public Entity[] getLocalEntitiesCopy() {
        return Arrays.copyOf(this.allEntities.getRawData(), this.allEntities.size(), Entity[].class);
    }

    public List<ServerPlayer> getLocalPlayers() {
        return this.localPlayers;
    }

    public void addEntityTickingEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        this.entityTickList.add(entity);
    }

    public boolean hasEntityTickingEntity(final Entity entity) {
        return this.entityTickList.contains(entity);
    }

    public void removeEntityTickingEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        this.entityTickList.remove(entity);
    }

    public void forEachTickingEntity(final Consumer<Entity> action) {
        final IteratorSafeOrderedReferenceSet.Iterator<Entity> iterator = this.entityTickList.iterator();
        try {
            while (iterator.hasNext()) {
                action.accept(iterator.next());
            }
        } finally {
            iterator.finishedIterating();
        }
    }

    public void addEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(this.world, entity.chunkPosition())) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        if (this.allEntities.add(entity)) {
            if (entity instanceof ServerPlayer player) {
                this.localPlayers.add(player);
            }
        }
    }

    public boolean hasEntity(final Entity entity) {
        return this.allEntities.contains(entity);
    }

    public void removeEntity(final Entity entity) {
        if (!TickThread.isTickThreadFor(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not under this region's control");
        }
        if (this.allEntities.remove(entity)) {
            if (entity instanceof ServerPlayer player) {
                this.localPlayers.remove(player);
            }
        }
    }

    public void addNavigatingMob(final Mob mob) {
        if (!TickThread.isTickThreadFor(mob)) {
            throw new IllegalArgumentException("Entity " + mob + " is not under this region's control");
        }
        this.navigatingMobs.add(mob);
    }

    public void removeNavigatingMob(final Mob mob) {
        if (!TickThread.isTickThreadFor(mob)) {
            throw new IllegalArgumentException("Entity " + mob + " is not under this region's control");
        }
        this.navigatingMobs.remove(mob);
    }

    public Iterator<Mob> getNavigatingMobs() {
        return this.navigatingMobs.unsafeIterator();
    }

    // block ticking hooks
    // Since block event data does not require chunk holders to be created for the chunk they reside in,
    // it's not actually guaranteed that when merging / splitting data that we actually own the data...
    // Note that we can only ever not own the event data when the chunk unloads, and so I've decided to
    // make the code easier by simply discarding it in such an event
    public void pushBlockEvent(final BlockEventData blockEventData) {
        TickThread.ensureTickThread(this.world, blockEventData.pos(), "Cannot queue block even data async");
        this.blockEvents.add(blockEventData);
    }

    public void pushBlockEvents(final Collection<? extends BlockEventData> blockEvents) {
        for (final BlockEventData blockEventData : blockEvents) {
            this.pushBlockEvent(blockEventData);
        }
    }

    public void removeIfBlockEvents(final Predicate<? super BlockEventData> predicate) {
        for (final Iterator<BlockEventData> iterator = this.blockEvents.iterator(); iterator.hasNext();) {
            final BlockEventData blockEventData = iterator.next();
            if (predicate.test(blockEventData)) {
                iterator.remove();
            }
        }
    }

    public BlockEventData removeFirstBlockEvent() {
        BlockEventData ret;
        while (!this.blockEvents.isEmpty()) {
            ret = this.blockEvents.removeFirst();
            if (TickThread.isTickThreadFor(this.world, ret.pos())) {
                return ret;
            } // else: chunk must have been unloaded
        }

        return null;
    }

    public LevelTicks<Block> getBlockLevelTicks() {
        return this.blockLevelTicks;
    }

    public LevelTicks<Fluid> getFluidLevelTicks() {
        return this.fluidLevelTicks;
    }

    // tile entity ticking
    public void addBlockEntityTicker(final TickingBlockEntity ticker) {
        TickThread.ensureTickThread(this.world, ticker.getPos(), "Tile entity must be owned by current region");

        (this.tickingBlockEntities ? this.pendingBlockEntityTickers : this.blockEntityTickers).add(ticker);
    }

    public void seTtickingBlockEntities(final boolean to) {
        this.tickingBlockEntities = true;
    }

    public List<TickingBlockEntity> getBlockEntityTickers() {
        return this.blockEntityTickers;
    }

    public void pushPendingTickingBlockEntities() {
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
            this.pendingBlockEntityTickers.clear();
        }
    }

    // ticking chunks
    public void addEntityTickingChunks(final LevelChunk levelChunk) {
        this.entityTickingChunks.add(levelChunk);
    }

    public void removeEntityTickingChunk(final LevelChunk levelChunk) {
        this.entityTickingChunks.remove(levelChunk);
    }

    public IteratorSafeOrderedReferenceSet<LevelChunk> getEntityTickingChunks() {
        return this.entityTickingChunks;
    }
}
