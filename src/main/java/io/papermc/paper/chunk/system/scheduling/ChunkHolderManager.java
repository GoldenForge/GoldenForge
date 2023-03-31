package io.papermc.paper.chunk.system.scheduling;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.map.SWMRLong2ObjectHashTable;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.papermc.paper.chunk.system.io.RegionFileIOThread;
import io.papermc.paper.chunk.system.poi.PoiChunk;
import io.papermc.paper.util.CoordinateUtils;
import io.papermc.paper.util.TickThread;
import io.papermc.paper.util.misc.Delayed8WayDistancePropagator2D;
import io.papermc.paper.world.ChunkEntitySlices;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import io.papermc.paper.chunk.system.ChunkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

// Folia start - region threading
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
// Folia end - region threading

public final class ChunkHolderManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int FULL_LOADED_TICKET_LEVEL    = 33;
    public static final int BLOCK_TICKING_TICKET_LEVEL  = 32;
    public static final int ENTITY_TICKING_TICKET_LEVEL = 31;
    public static final int MAX_TICKET_LEVEL = ChunkMap.MAX_CHUNK_DISTANCE; // inclusive

    // Folia start - region threading
    private static final long NO_TIMEOUT_MARKER = Long.MIN_VALUE;
    private static final long PROBE_MARKER = Long.MIN_VALUE + 1;
    // special region threading fields
    // this field contains chunk holders that were created in addTicketAtLevel
    // because the chunk holders were created without a reliable unload hook (i.e creation for entity/poi loading,
    // which always check for unload after their tasks finish) we need to do that ourselves later
    private final ReferenceOpenHashSet<NewChunkHolder> specialCaseUnload = new ReferenceOpenHashSet<>();
    // Folia end - region threading

    public final ReentrantLock ticketLock = new ReentrantLock(); // Folia - region threading

    private final SWMRLong2ObjectHashTable<NewChunkHolder> chunkHolders = new SWMRLong2ObjectHashTable<>(16384, 0.25f);
    // Folia - region threading
    private final ServerLevel world;
    private final ChunkTaskScheduler taskScheduler;

    // Folia start - region threading
    public static final class HolderManagerRegionData {
        /*
         * This region data is a bit of a mess, because it is part global state and part region state.
         * Typically for region state we do not need to worry about threading concerns because it is only
         * accessed by the current region when ticking. But since this contains state (
         * tickets, and removeTickToChunkExpireTicketCount) that can be written to by any thread holding the
         * ticket lock, the merge logic is complicated as merging only holds the region lock. So, Folia has modified
         * the add and remove ticket functions to acquire the region lock if the current region does not own the target
         * position.
         */
        private final ArrayDeque<NewChunkHolder> pendingFullLoadUpdate = new ArrayDeque<>();
        private final ObjectRBTreeSet<NewChunkHolder> autoSaveQueue = new ObjectRBTreeSet<>((final NewChunkHolder c1, final NewChunkHolder c2) -> {
            if (c1 == c2) {
                return 0;
            }

            final int saveTickCompare = Long.compare(c1.lastAutoSave, c2.lastAutoSave);

            if (saveTickCompare != 0) {
                return saveTickCompare;
            }

            final long coord1 = CoordinateUtils.getChunkKey(c1.chunkX, c1.chunkZ);
            final long coord2 = CoordinateUtils.getChunkKey(c2.chunkX, c2.chunkZ);

            if (coord1 == coord2) {
                throw new IllegalStateException("Duplicate chunkholder in auto save queue");
            }

            return Long.compare(coord1, coord2);
        });
        private long currentTick;
        private final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets = new Long2ObjectOpenHashMap<>(8192, 0.25f);
        // what a disaster of a name
        // this is a map of removal tick to a map of chunks and the number of tickets a chunk has that are to expire that tick
        private final Long2ObjectOpenHashMap<Long2IntOpenHashMap> removeTickToChunkExpireTicketCount = new Long2ObjectOpenHashMap<>();

        public void merge(final HolderManagerRegionData into, final long tickOffset) {
            // Order doesn't really matter for the pending full update...
            into.pendingFullLoadUpdate.addAll(this.pendingFullLoadUpdate);

            // We need to copy the set to iterate over, because modifying the field used in compareTo while iterating
            // will destroy the result from compareTo (However, the set is not destroyed _after_ iteration because a constant
            // addition to every entry will not affect compareTo).
            for (final NewChunkHolder holder : new ArrayList<>(this.autoSaveQueue)) {
                holder.lastAutoSave += tickOffset;
                into.autoSaveQueue.add(holder);
            }

            final long chunkManagerTickOffset = into.currentTick - this.currentTick;
            for (final Iterator<Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>>> iterator = this.tickets.long2ObjectEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry = iterator.next();
                final SortedArraySet<Ticket<?>> oldTickets = entry.getValue();
                final SortedArraySet<Ticket<?>> newTickets = SortedArraySet.create(Math.max(4, oldTickets.size() + 1));
                for (final Ticket<?> ticket : oldTickets) {
                    newTickets.add(
                            new Ticket(ticket.getType(), ticket.getTicketLevel(), ticket.key,
                                    ticket.createdTick == NO_TIMEOUT_MARKER ? NO_TIMEOUT_MARKER : ticket.createdTick + chunkManagerTickOffset)
                    );
                }
                into.tickets.put(entry.getLongKey(), newTickets);
            }
            for (final Iterator<Long2ObjectMap.Entry<Long2IntOpenHashMap>> iterator = this.removeTickToChunkExpireTicketCount.long2ObjectEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ObjectMap.Entry<Long2IntOpenHashMap> entry = iterator.next();
                into.removeTickToChunkExpireTicketCount.merge(
                        (long)(entry.getLongKey() + chunkManagerTickOffset), entry.getValue(),
                        (final Long2IntOpenHashMap t, final Long2IntOpenHashMap f) -> {
                            for (final Iterator<Long2IntMap.Entry> itr = f.long2IntEntrySet().fastIterator(); itr.hasNext();) {
                                final Long2IntMap.Entry e = itr.next();
                                t.addTo(e.getLongKey(), e.getIntValue());
                            }
                            return t;
                        }
                );
            }
        }

        public void split(final int chunkToRegionShift, final Long2ReferenceOpenHashMap<HolderManagerRegionData> regionToData,
                          final ReferenceOpenHashSet<HolderManagerRegionData> dataSet) {
            for (final NewChunkHolder fullLoadUpdate : this.pendingFullLoadUpdate) {
                final int regionCoordinateX = fullLoadUpdate.chunkX >> chunkToRegionShift;
                final int regionCoordinateZ = fullLoadUpdate.chunkZ >> chunkToRegionShift;

                final HolderManagerRegionData data = regionToData.get(CoordinateUtils.getChunkKey(regionCoordinateX, regionCoordinateZ));
                if (data != null) {
                    data.pendingFullLoadUpdate.add(fullLoadUpdate);
                } // else: fullLoadUpdate is an unloaded chunk holder
            }

            for (final NewChunkHolder autoSave : this.autoSaveQueue) {
                final int regionCoordinateX = autoSave.chunkX >> chunkToRegionShift;
                final int regionCoordinateZ = autoSave.chunkZ >> chunkToRegionShift;

                final HolderManagerRegionData data = regionToData.get(CoordinateUtils.getChunkKey(regionCoordinateX, regionCoordinateZ));
                if (data != null) {
                    data.autoSaveQueue.add(autoSave);
                } // else: autoSave is an unloaded chunk holder
            }
            for (final HolderManagerRegionData data : dataSet) {
                data.currentTick = this.currentTick;
            }
            for (final Iterator<Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>>> iterator = this.tickets.long2ObjectEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry = iterator.next();
                final long chunkKey = entry.getLongKey();
                final int regionCoordinateX = CoordinateUtils.getChunkX(chunkKey) >> chunkToRegionShift;
                final int regionCoordinateZ = CoordinateUtils.getChunkZ(chunkKey) >> chunkToRegionShift;

                // can never be null, since a chunk holder exists if the ticket set is not empty
                regionToData.get(CoordinateUtils.getChunkKey(regionCoordinateX, regionCoordinateZ)).tickets.put(chunkKey, entry.getValue());
            }
            for (final Iterator<Long2ObjectMap.Entry<Long2IntOpenHashMap>> iterator = this.removeTickToChunkExpireTicketCount.long2ObjectEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ObjectMap.Entry<Long2IntOpenHashMap> entry = iterator.next();
                final long tick = entry.getLongKey();
                final Long2IntOpenHashMap chunkToCount = entry.getValue();

                for (final Iterator<Long2IntMap.Entry> itr = chunkToCount.long2IntEntrySet().fastIterator(); itr.hasNext();) {
                    final Long2IntMap.Entry e = itr.next();
                    final long chunkKey = e.getLongKey();
                    final int regionCoordinateX = CoordinateUtils.getChunkX(chunkKey) >> chunkToRegionShift;
                    final int regionCoordinateZ = CoordinateUtils.getChunkZ(chunkKey) >> chunkToRegionShift;
                    final int count = e.getIntValue();

                    // can never be null, since a chunk holder exists if the ticket set is not empty
                    final HolderManagerRegionData data = regionToData.get(CoordinateUtils.getChunkKey(regionCoordinateX, regionCoordinateZ));

                    data.removeTickToChunkExpireTicketCount.computeIfAbsent(tick, (final long keyInMap) -> {
                        return new Long2IntOpenHashMap();
                    }).put(chunkKey, count);
                }
            }
        }
    }

    private ChunkHolderManager.HolderManagerRegionData getCurrentRegionData() {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();

        if (region == null) {
            return null;
        }

        if (this.world != null && this.world != region.getData().world) {
            throw new IllegalStateException("World check failed: expected world: " + this.world.getWorld().getName() + ", region world: " + region.getData().world.getWorld().getName());
        }

        return region.getData().getHolderManagerRegionData();
    }

    // MUST hold ticket lock
    private ChunkHolderManager.HolderManagerRegionData getDataFor(final long key) {
        return this.getDataFor(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key));
    }

    // MUST hold ticket lock
    private ChunkHolderManager.HolderManagerRegionData getDataFor(final int chunkX, final int chunkZ) {
        if (!this.ticketLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Must hold ticket level lock");
        }

        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region
                = this.world.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);

        if (region == null) {
            return null;
        }

        return region.getData().getHolderManagerRegionData();
    }
    // Folia end - region threading


    public ChunkHolderManager(final ServerLevel world, final ChunkTaskScheduler taskScheduler) {
        this.world = world;
        this.taskScheduler = taskScheduler;
    }

    private long statusUpgradeId;

    long getNextStatusUpgradeId() {
        return ++this.statusUpgradeId;
    }

    public List<ChunkHolder> getOldChunkHolders() {
        final List<NewChunkHolder> holders = this.getChunkHolders();
        final List<ChunkHolder> ret = new ArrayList<>(holders.size());
        for (final NewChunkHolder holder : holders) {
            ret.add(holder.vanillaChunkHolder);
        }
        return ret;
    }

    public List<NewChunkHolder> getChunkHolders() {
        final List<NewChunkHolder> ret = new ArrayList<>(this.chunkHolders.size());
        this.chunkHolders.forEachValue(ret::add);
        return ret;
    }

    public int size() {
        return this.chunkHolders.size();
    }

    public void close(final boolean save, final boolean halt) {
        // Folia start - region threading
        this.close(save, halt, true, true, true);
    }
    public void close(final boolean save, final boolean halt, final boolean first, final boolean last, final boolean checkRegions) {
        // Folia end - region threading
        TickThread.ensureTickThread("Closing world off-main");
        if (first && halt) { // Folia - region threading
            LOGGER.info("Waiting 60s for chunk system to halt for world '" + this.world.getWorld().getName() + "'");
            if (!this.taskScheduler.halt(true, TimeUnit.SECONDS.toNanos(60L))) {
                LOGGER.warn("Failed to halt world generation/loading tasks for world '" + this.world.getWorld().getName() + "'");
            } else {
                LOGGER.info("Halted chunk system for world '" + this.world.getWorld().getName() + "'");
            }
        }

        if (save) {
            this.saveAllChunksRegionised(true, true, true, first, last, checkRegions); // Folia - region threading
        }

        if (last) { // Folia - region threading
            if (this.world.chunkDataControllerNew.hasTasks() || this.world.entityDataControllerNew.hasTasks() || this.world.poiDataControllerNew.hasTasks()) {
                RegionFileIOThread.flush();
            }

            // kill regionfile cache
            try {
                this.world.chunkDataControllerNew.getCache().close();
            } catch (final IOException ex) {
                LOGGER.error("Failed to close chunk regionfile cache for world '" + this.world.getWorld().getName() + "'", ex);
            }
            try {
                this.world.entityDataControllerNew.getCache().close();
            } catch (final IOException ex) {
                LOGGER.error("Failed to close entity regionfile cache for world '" + this.world.getWorld().getName() + "'", ex);
            }
            try {
                this.world.poiDataControllerNew.getCache().close();
            } catch (final IOException ex) {
                LOGGER.error("Failed to close poi regionfile cache for world '" + this.world.getWorld().getName() + "'", ex);
            }
        } // Folia - region threading
    }

    void ensureInAutosave(final NewChunkHolder holder) {
        // Folia start - region threading
        final HolderManagerRegionData regionData = this.getCurrentRegionData();
        if (!regionData.autoSaveQueue.contains(holder)) {
            holder.lastAutoSave = RegionizedServer.getCurrentTick();
            // Folia end - region threading
            regionData.autoSaveQueue.add(holder);
        }
    }

    public void autoSave() {
        final List<NewChunkHolder> reschedule = new ArrayList<>();
        final long currentTick = RegionizedServer.getCurrentTick();
        final long maxSaveTime = currentTick - 6000;
        // Folia start - region threading
        final HolderManagerRegionData regionData = this.getCurrentRegionData();
        for (int autoSaved = 0; autoSaved < 24 && !regionData.autoSaveQueue.isEmpty();) {
            // Folia end - region threading
            final NewChunkHolder holder = regionData.autoSaveQueue.first();

            if (holder.lastAutoSave > maxSaveTime) {
                break;
            }

            regionData.autoSaveQueue.remove(holder);

            holder.lastAutoSave = currentTick;
            if (holder.save(false, false) != null) {
                ++autoSaved;
            }

            if (holder.getChunkStatus().isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                reschedule.add(holder);
            }
        }

        for (final NewChunkHolder holder : reschedule) {
            if (holder.getChunkStatus().isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                regionData.autoSaveQueue.add(holder);
            }
        }
    }

    public void saveAllChunks(final boolean flush, final boolean shutdown, final boolean logProgress) {
        // Folia start - region threading
        this.saveAllChunksRegionised(flush, shutdown, logProgress, true, true, true);
    }
    public void saveAllChunksRegionised(final boolean flush, final boolean shutdown, final boolean logProgress, final boolean first, final boolean last, final boolean checkRegion) {
        // Folia end - region threading
        final List<NewChunkHolder> holders = this.getChunkHolders();

        if (first && logProgress) { // Folia - region threading
            LOGGER.info("Saving all chunkholders for world '" + this.world.getWorld().getName() + "'");
        }

        final DecimalFormat format = new DecimalFormat("#0.00");

        int saved = 0;

        final long start = System.nanoTime();
        long lastLog = start;
        boolean needsFlush = false;
        final int flushInterval = 50;

        int savedChunk = 0;
        int savedEntity = 0;
        int savedPoi = 0;

        for (int i = 0, len = holders.size(); i < len; ++i) {
            final NewChunkHolder holder = holders.get(i);
            // Folia start - region threading
            if (!checkRegion && !TickThread.isTickThreadFor(this.world, holder.chunkX, holder.chunkZ)) {
                // skip holders that would fail the thread check
                continue;
            }
            // Folia end - region threading
            try {
                final NewChunkHolder.SaveStat saveStat = holder.save(shutdown, false);
                if (saveStat != null) {
                    ++saved;
                    needsFlush = flush;
                    if (saveStat.savedChunk()) {
                        ++savedChunk;
                    }
                    if (saveStat.savedEntityChunk()) {
                        ++savedEntity;
                    }
                    if (saveStat.savedPoiChunk()) {
                        ++savedPoi;
                    }
                }
            } catch (final ThreadDeath thr) {
                throw thr;
            } catch (final Throwable thr) {
                LOGGER.error("Failed to save chunk (" + holder.chunkX + "," + holder.chunkZ + ") in world '" + this.world.getWorld().getName() + "'", thr);
            }
            if (needsFlush && (saved % flushInterval) == 0) {
                needsFlush = false;
                RegionFileIOThread.partialFlush(flushInterval / 2);
            }
            if (logProgress) {
                final long currTime = System.nanoTime();
                if ((currTime - lastLog) > TimeUnit.SECONDS.toNanos(10L)) {
                    lastLog = currTime;
                    LOGGER.info("Saved " + saved + " chunks (" + format.format((double)(i+1)/(double)len * 100.0) + "%) in world '" + this.world.getWorld().getName() + "'");
                }
            }
        }
        if (last && flush) { // Folia - region threading
            RegionFileIOThread.flush();
        }
        if (logProgress) {
            LOGGER.info("Saved " + savedChunk + " block chunks, " + savedEntity + " entity chunks, " + savedPoi + " poi chunks in world '" + this.world.getWorld().getName() + "' in " + format.format(1.0E-9 * (System.nanoTime() - start)) + "s");
        }
    }

    protected final Long2IntLinkedOpenHashMap ticketLevelUpdates = new Long2IntLinkedOpenHashMap() {
        @Override
        protected void rehash(final int newN) {
            // no downsizing allowed
            if (newN < this.n) {
                return;
            }
            super.rehash(newN);
        }
    };

    protected final Delayed8WayDistancePropagator2D ticketLevelPropagator = new Delayed8WayDistancePropagator2D(
            (final long coordinate, final byte oldLevel, final byte newLevel) -> {
                ChunkHolderManager.this.ticketLevelUpdates.putAndMoveToLast(coordinate, convertBetweenTicketLevels(newLevel));
            }
    );
    // function for converting between ticket levels and propagator levels and vice versa
    // the problem is the ticket level propagator will propagate from a set source down to zero, whereas mojang expects
    // levels to propagate from a set value up to a maximum value. so we need to convert the levels we put into the propagator
    // and the levels we get out of the propagator

    public static int convertBetweenTicketLevels(final int level) {
        return ChunkMap.MAX_CHUNK_DISTANCE - level + 1;
    }

    public boolean hasTickets() {
        return !this.getTicketsCopy().isEmpty(); // Folia - region threading
    }

    public String getTicketDebugString(final long coordinate) {
        this.ticketLock.lock();
        try {
            // Folia start - region threading
            final ChunkHolderManager.HolderManagerRegionData holderManagerRegionData = this.getDataFor(coordinate);
            final SortedArraySet<Ticket<?>> tickets = holderManagerRegionData == null ? null : holderManagerRegionData.tickets.get(coordinate);
            // Folia end - region threading

            return tickets != null ? tickets.first().toString() : "no_ticket";
        } finally {
            this.ticketLock.unlock();
        }
    }

    public Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> getTicketsCopy() {
        this.ticketLock.lock();
        try {
            // Folia start - region threading
            Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ret = new Long2ObjectOpenHashMap<>();
            this.world.regioniser.computeForAllRegions((region) -> {
                for (final LongIterator iterator = region.getData().getHolderManagerRegionData().tickets.keySet().longIterator(); iterator.hasNext();) {
                    final long chunk = iterator.nextLong();

                    ret.put(chunk, region.getData().getHolderManagerRegionData().tickets.get(chunk));
                }
            });
            return ret;
            // Folia end - region threading
        } finally {
            this.ticketLock.unlock();
        }
    }

    protected final int getPropagatedTicketLevel(final long coordinate) {
        return convertBetweenTicketLevels(this.ticketLevelPropagator.getLevel(coordinate));
    }

    protected final void updateTicketLevel(final long coordinate, final int ticketLevel) {
        if (ticketLevel > ChunkMap.MAX_CHUNK_DISTANCE) {
            this.ticketLevelPropagator.removeSource(coordinate);
        } else {
            this.ticketLevelPropagator.setSource(coordinate, convertBetweenTicketLevels(ticketLevel));
        }
    }

    private static int getTicketLevelAt(SortedArraySet<Ticket<?>> tickets) {
        return !tickets.isEmpty() ? tickets.first().getTicketLevel() : MAX_TICKET_LEVEL + 1;
    }

    public <T> boolean addTicketAtLevel(final TicketType<T> type, final ChunkPos chunkPos, final int level,
                                        final T identifier) {
        return this.addTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkPos), level, identifier);
    }

    public <T> boolean addTicketAtLevel(final TicketType<T> type, final int chunkX, final int chunkZ, final int level,
                                        final T identifier) {
        return this.addTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkX, chunkZ), level, identifier);
    }

    // supposed to return true if the ticket was added and did not replace another
    // but, we always return false if the ticket cannot be added
    public <T> boolean addTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier) {
        final long removeDelay = Math.max(0, type.timeout);
        if (level > MAX_TICKET_LEVEL) {
            return false;
        }

        // Folia start - region threading
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currRegion = TickRegionScheduler.getCurrentRegion();
        final boolean lock = currRegion == null || this.world.regioniser.getRegionAtUnsynchronised(
                CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk)
        ) != currRegion;
        // Folia end - region threading

        this.ticketLock.lock();
        try {
            // Folia start - region threading
            NewChunkHolder holder = this.chunkHolders.get(chunk);
            if (holder == null) {
                // we need to guarantee that a chunk holder exists for each ticket
                // this must be executed before retrieving the holder manager data for a target chunk, to ensure the
                // region will exist
                this.chunkHolders.put(chunk, holder = this.createChunkHolder(chunk));
                this.specialCaseUnload.add(holder);
            }

            if (lock) {
                // we just need to prevent merging, so we only need the read lock
                // additionally, this will prevent deadlock in the remove all tickets function by using the read lock
                this.world.regioniser.acquireReadLock();
            }
            try {
                final ChunkHolderManager.HolderManagerRegionData targetData = lock ? this.getDataFor(chunk) : currRegion.getData().getHolderManagerRegionData();
                // Folia end - region threading
                final long removeTick = removeDelay == 0 ? NO_TIMEOUT_MARKER : targetData.currentTick + removeDelay; // Folia - region threading
                final Ticket<T> ticket = new Ticket<>(type, level, identifier, removeTick);

                final SortedArraySet<Ticket<?>> ticketsAtChunk = targetData.tickets.computeIfAbsent(chunk, (final long keyInMap) -> { // Folia - region threading
                    return SortedArraySet.create(4);
                });

                final int levelBefore = getTicketLevelAt(ticketsAtChunk);
                final Ticket<T> current = (Ticket<T>)ticketsAtChunk.replace(ticket);
                final int levelAfter = getTicketLevelAt(ticketsAtChunk);

                if (current != ticket) {
                    final long oldRemovalTick = current.createdTick;
                    if (removeTick != oldRemovalTick) {
                        if (oldRemovalTick != NO_TIMEOUT_MARKER) {
                            final Long2IntOpenHashMap removeCounts = targetData.removeTickToChunkExpireTicketCount.get(oldRemovalTick); // Folia - region threading
                            final int prevCount = removeCounts.addTo(chunk, -1);

                            if (prevCount == 1) {
                                removeCounts.remove(chunk);
                                if (removeCounts.isEmpty()) {
                                    targetData.removeTickToChunkExpireTicketCount.remove(oldRemovalTick); // Folia - region threading
                                }
                            }
                        }
                        if (removeTick != NO_TIMEOUT_MARKER) {
                            targetData.removeTickToChunkExpireTicketCount.computeIfAbsent(removeTick, (final long keyInMap) -> { // Folia - region threading
                                return new Long2IntOpenHashMap();
                            }).addTo(chunk, 1);
                        }
                    }
                } else {
                    if (removeTick != NO_TIMEOUT_MARKER) {
                        targetData.removeTickToChunkExpireTicketCount.computeIfAbsent(removeTick, (final long keyInMap) -> { // Folia - region threading
                            return new Long2IntOpenHashMap();
                        }).addTo(chunk, 1);
                    }
                }

                if (levelBefore != levelAfter) {
                    this.updateTicketLevel(chunk, levelAfter);
                }

                return current == ticket;
            } finally { // Folia start - region threading
                if (lock) {
                    this.world.regioniser.releaseReadLock();
                }
            } // Folia end - region threading
        } finally {
            this.ticketLock.unlock();
        }
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final ChunkPos chunkPos, final int level, final T identifier) {
        return this.removeTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkPos), level, identifier);
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final int chunkX, final int chunkZ, final int level, final T identifier) {
        return this.removeTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkX, chunkZ), level, identifier);
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier) {
        if (level > MAX_TICKET_LEVEL) {
            return false;
        }

        // Folia start - region threading
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currRegion = TickRegionScheduler.getCurrentRegion();
        final boolean lock = currRegion == null || this.world.regioniser.getRegionAtUnsynchronised(
                CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk)
        ) != currRegion;
        // Folia end - region threading

        this.ticketLock.lock();
        try {
            // Folia start - region threading
            if (lock) {
                // we just need to prevent merging, so we only need the read lock
                // additionally, this will prevent deadlock in the remove all tickets function by using the read lock
                this.world.regioniser.acquireReadLock();
            }
            try {
                final ChunkHolderManager.HolderManagerRegionData targetData = lock ? this.getDataFor(chunk) : currRegion.getData().getHolderManagerRegionData();
                // Folia end - region threading

                final SortedArraySet<Ticket<?>> ticketsAtChunk = targetData == null ? null : targetData.tickets.get(chunk);
                // Folia end - region threading
                if (ticketsAtChunk == null) {
                    return false;
                }

                final int oldLevel = getTicketLevelAt(ticketsAtChunk);
                final Ticket<T> ticket = (Ticket<T>)ticketsAtChunk.removeAndGet(new Ticket<>(type, level, identifier, PROBE_MARKER)); // Folia - region threading

                if (ticket == null) {
                    return false;
                }

                int newLevel = getTicketLevelAt(ticketsAtChunk); // Folia - region threading - moved up from below
                // Folia start - region threading
                // we should not change the ticket levels while the target region may be ticking
                if (newLevel > level) {
                    final long unknownRemoveTick = targetData.currentTick + Math.max(0, TicketType.UNKNOWN.timeout);
                    final Ticket<ChunkPos> unknownTicket = new Ticket<>(TicketType.UNKNOWN, level, new ChunkPos(chunk), unknownRemoveTick);
                    if (ticketsAtChunk.add(unknownTicket)) {
                        targetData.removeTickToChunkExpireTicketCount.computeIfAbsent(unknownRemoveTick, (final long keyInMap) -> {
                            return new Long2IntOpenHashMap();
                        }).addTo(chunk, 1);
                    } else {
                        throw new IllegalStateException("Should have been able to add " + unknownTicket + " to " + ticketsAtChunk);
                    }
                    newLevel = level;
                }
                // Folia end - region threading

                if (ticketsAtChunk.isEmpty()) {
                    targetData.tickets.remove(chunk); // Folia - region threading
                }

                // Folia - region threading - move up

                final long removeTick = ticket.createdTick;
                if (removeTick != NO_TIMEOUT_MARKER) {
                    final Long2IntOpenHashMap removeCounts = targetData.removeTickToChunkExpireTicketCount.get(removeTick); // Folia - region threading
                    final int currCount = removeCounts.addTo(chunk, -1);

                    if (currCount == 1) {
                        removeCounts.remove(chunk);
                        if (removeCounts.isEmpty()) {
                            targetData.removeTickToChunkExpireTicketCount.remove(removeTick); // Folia - region threading
                        }
                    }
                }

                if (oldLevel != newLevel) {
                    this.updateTicketLevel(chunk, newLevel);
                }

                return true;
            } finally { // Folia start - region threading
                if (lock) {
                    this.world.regioniser.releaseReadLock();
                }
            } // Folia end - region threading
        } finally {
            this.ticketLock.unlock();
        }
    }

    // atomic with respect to all add/remove/addandremove ticket calls for the given chunk
    public <T, V> void addAndRemoveTickets(final long chunk, final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                           final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
        this.ticketLock.lock();
        try {
            this.addTicketAtLevel(addType, chunk, addLevel, addIdentifier);
            this.removeTicketAtLevel(removeType, chunk, removeLevel, removeIdentifier);
        } finally {
            this.ticketLock.unlock();
        }
    }

    // atomic with respect to all add/remove/addandremove ticket calls for the given chunk
    public <T, V> boolean addIfRemovedTicket(final long chunk, final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                             final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
        this.ticketLock.lock();
        try {
            if (this.removeTicketAtLevel(removeType, chunk, removeLevel, removeIdentifier)) {
                this.addTicketAtLevel(addType, chunk, addLevel, addIdentifier);
                return true;
            }
            return false;
        } finally {
            this.ticketLock.unlock();
        }
    }

    public <T> void removeAllTicketsFor(final TicketType<T> ticketType, final int ticketLevel, final T ticketIdentifier) {
        if (ticketLevel > MAX_TICKET_LEVEL) {
            return;
        }

        this.ticketLock.lock();
        try {
            // Folia start - region threading
            this.world.regioniser.computeForAllRegions((region) -> {
                for (final LongIterator iterator = new LongArrayList(region.getData().getHolderManagerRegionData().tickets.keySet()).longIterator(); iterator.hasNext();) {
                    final long chunk = iterator.nextLong();

                    this.removeTicketAtLevel(ticketType, chunk, ticketLevel, ticketIdentifier);
                }
            });
            // Folia end - region threading
        } finally {
            this.ticketLock.unlock();
        }
    }

    public void tick() {
        // Folia start - region threading
        final ChunkHolderManager.HolderManagerRegionData data = this.getCurrentRegionData();
        if (data == null) {
            throw new IllegalStateException("Not running tick() while on a region");
        }
        // Folia end - region threading

        this.ticketLock.lock();
        try {
            final long tick = ++data.currentTick; // Folia - region threading

            final Long2IntOpenHashMap toRemove = data.removeTickToChunkExpireTicketCount.remove(tick); // Folia - region threading

            if (toRemove == null) {
                return;
            }

            final Predicate<Ticket<?>> expireNow = (final Ticket<?> ticket) -> {
                return ticket.createdTick == tick;
            };

            for (final LongIterator iterator = toRemove.keySet().longIterator(); iterator.hasNext();) {
                final long chunk = iterator.nextLong();

                final SortedArraySet<Ticket<?>> tickets = data.tickets.get(chunk); // Folia - region threading
                tickets.removeIf(expireNow);
                if (tickets.isEmpty()) {
                    data.tickets.remove(chunk); // Folia - region threading
                    this.ticketLevelPropagator.removeSource(chunk);
                } else {
                    this.ticketLevelPropagator.setSource(chunk, convertBetweenTicketLevels(tickets.first().getTicketLevel()));
                }
            }
        } finally {
            this.ticketLock.unlock();
        }

        this.processTicketUpdates();
    }

    public NewChunkHolder getChunkHolder(final int chunkX, final int chunkZ) {
        return this.chunkHolders.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    public NewChunkHolder getChunkHolder(final long position) {
        return this.chunkHolders.get(position);
    }

    public void raisePriority(final int x, final int z, final PrioritisedExecutor.Priority priority) {
        final NewChunkHolder chunkHolder = this.getChunkHolder(x, z);
        if (chunkHolder != null) {
            chunkHolder.raisePriority(priority);
        }
    }

    public void setPriority(final int x, final int z, final PrioritisedExecutor.Priority priority) {
        final NewChunkHolder chunkHolder = this.getChunkHolder(x, z);
        if (chunkHolder != null) {
            chunkHolder.setPriority(priority);
        }
    }

    public void lowerPriority(final int x, final int z, final PrioritisedExecutor.Priority priority) {
        final NewChunkHolder chunkHolder = this.getChunkHolder(x, z);
        if (chunkHolder != null) {
            chunkHolder.lowerPriority(priority);
        }
    }

    private NewChunkHolder createChunkHolder(final long position) {
        final NewChunkHolder ret = new NewChunkHolder(this.world, CoordinateUtils.getChunkX(position), CoordinateUtils.getChunkZ(position), this.taskScheduler);

        ChunkSystem.onChunkHolderCreate(this.world, ret.vanillaChunkHolder);
        ret.vanillaChunkHolder.onChunkAdd();

        return ret;
    }

    // because this function creates the chunk holder without a ticket, it is the caller's responsibility to ensure
    // the chunk holder eventually unloads. this should only be used to avoid using processTicketUpdates to create chunkholders,
    // as processTicketUpdates may call plugin logic; in every other case a ticket is appropriate
    private NewChunkHolder getOrCreateChunkHolder(final int chunkX, final int chunkZ) {
        return this.getOrCreateChunkHolder(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    private NewChunkHolder getOrCreateChunkHolder(final long position) {
        if (!this.ticketLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Must hold ticket level update lock!");
        }
        if (!this.taskScheduler.schedulingLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Must hold scheduler lock!!");
        }

        // we could just acquire these locks, but...
        // must own the locks because the caller needs to ensure that no unload can occur AFTER this function returns

        NewChunkHolder current = this.chunkHolders.get(position);
        if (current != null) {
            return current;
        }

        current = this.createChunkHolder(position);
        this.chunkHolders.put(position, current);

        return current;
    }

    private long entityLoadCounter;

    public ChunkEntitySlices getOrCreateEntityChunk(final int chunkX, final int chunkZ, final boolean transientChunk) {
        TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Cannot create entity chunk off-main");
        ChunkEntitySlices ret;

        NewChunkHolder current = this.getChunkHolder(chunkX, chunkZ);
        if (current != null && (ret = current.getEntityChunk()) != null && (transientChunk || !ret.isTransient())) {
            return ret;
        }

        final AtomicBoolean isCompleted = new AtomicBoolean();
        final Thread waiter = Thread.currentThread();
        final Long entityLoadId;
        NewChunkHolder.GenericDataLoadTaskCallback loadTask = null;
        this.ticketLock.lock();
        try {
            entityLoadId = Long.valueOf(this.entityLoadCounter++);
            this.addTicketAtLevel(TicketType.ENTITY_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, entityLoadId);
            this.taskScheduler.schedulingLock.lock();
            try {
                current = this.getOrCreateChunkHolder(chunkX, chunkZ);
                if ((ret = current.getEntityChunk()) != null && (transientChunk || !ret.isTransient())) {
                    this.removeTicketAtLevel(TicketType.ENTITY_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, entityLoadId);
                    return ret;
                }

                if (current.isEntityChunkNBTLoaded()) {
                    isCompleted.setPlain(true);
                } else {
                    loadTask = current.getOrLoadEntityData((final GenericDataLoadTask.TaskResult<CompoundTag, Throwable> result) -> {
                        if (!transientChunk) {
                            isCompleted.set(true);
                            LockSupport.unpark(waiter);
                        }
                    });
                    final ChunkLoadTask.EntityDataLoadTask entityLoad = current.getEntityDataLoadTask();

                    if (entityLoad != null && !transientChunk) {
                        entityLoad.raisePriority(PrioritisedExecutor.Priority.BLOCKING);
                    }
                }
            } finally {
                this.taskScheduler.schedulingLock.unlock();
            }
        } finally {
            this.ticketLock.unlock();
        }

        if (loadTask != null) {
            loadTask.schedule();
        }

        if (!transientChunk) {
            // Note: no need to busy wait on the chunk queue, entity load will complete off-main
            boolean interrupted = false;
            while (!isCompleted.get()) {
                interrupted |= Thread.interrupted();
                LockSupport.park();
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        // now that the entity data is loaded, we can load it into the world

        ret = current.loadInEntityChunk(transientChunk);

        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        this.addAndRemoveTickets(chunkKey,
                TicketType.UNKNOWN, MAX_TICKET_LEVEL, new ChunkPos(chunkX, chunkZ),
                TicketType.ENTITY_LOAD, MAX_TICKET_LEVEL, entityLoadId
        );

        return ret;
    }

    public PoiChunk getPoiChunkIfLoaded(final int chunkX, final int chunkZ, final boolean checkLoadInCallback) {
        final NewChunkHolder holder = this.getChunkHolder(chunkX, chunkZ);
        if (holder != null) {
            final PoiChunk ret = holder.getPoiChunk();
            return ret == null || (checkLoadInCallback && !ret.isLoaded()) ? null : ret;
        }
        return null;
    }

    private long poiLoadCounter;

    public PoiChunk loadPoiChunk(final int chunkX, final int chunkZ) {
        TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Cannot create poi chunk off-main");
        PoiChunk ret;

        NewChunkHolder current = this.getChunkHolder(chunkX, chunkZ);
        if (current != null && (ret = current.getPoiChunk()) != null) {
            if (!ret.isLoaded()) {
                ret.load();
            }
            return ret;
        }

        final AtomicReference<PoiChunk> completed = new AtomicReference<>();
        final AtomicBoolean isCompleted = new AtomicBoolean();
        final Thread waiter = Thread.currentThread();
        final Long poiLoadId;
        NewChunkHolder.GenericDataLoadTaskCallback loadTask = null;
        this.ticketLock.lock();
        try {
            poiLoadId = Long.valueOf(this.poiLoadCounter++);
            this.addTicketAtLevel(TicketType.POI_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, poiLoadId);
            this.taskScheduler.schedulingLock.lock();
            try {
                current = this.getOrCreateChunkHolder(chunkX, chunkZ);
                if (current.isPoiChunkLoaded()) {
                    this.removeTicketAtLevel(TicketType.POI_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, poiLoadId);
                    return current.getPoiChunk();
                }

                loadTask = current.getOrLoadPoiData((final GenericDataLoadTask.TaskResult<PoiChunk, Throwable> result) -> {
                    completed.setPlain(result.left());
                    isCompleted.set(true);
                    LockSupport.unpark(waiter);
                });
                final ChunkLoadTask.PoiDataLoadTask poiLoad = current.getPoiDataLoadTask();

                if (poiLoad != null) {
                    poiLoad.raisePriority(PrioritisedExecutor.Priority.BLOCKING);
                }
            } finally {
                this.taskScheduler.schedulingLock.unlock();
            }
        } finally {
            this.ticketLock.unlock();
        }

        if (loadTask != null) {
            loadTask.schedule();
        }

        // Note: no need to busy wait on the chunk queue, poi load will complete off-main

        boolean interrupted = false;
        while (!isCompleted.get()) {
            interrupted |= Thread.interrupted();
            LockSupport.park();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        ret = completed.getPlain();

        ret.load();

        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        this.addAndRemoveTickets(chunkKey,
                TicketType.UNKNOWN, MAX_TICKET_LEVEL, new ChunkPos(chunkX, chunkZ),
                TicketType.POI_LOAD, MAX_TICKET_LEVEL, poiLoadId
        );

        return ret;
    }

    void addChangedStatuses(final List<NewChunkHolder> changedFullStatus) {
        if (changedFullStatus.isEmpty()) {
            return;
        }

        final Long2ObjectOpenHashMap<List<NewChunkHolder>> sectionToUpdates = new Long2ObjectOpenHashMap<>();
        final List<NewChunkHolder> thisRegionHolders = new ArrayList<>();

        final int regionShift = this.world.regioniser.sectionChunkShift;
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> thisRegion
                = TickRegionScheduler.getCurrentRegion();

        for (final NewChunkHolder holder : changedFullStatus) {
            final int regionX = holder.chunkX >> regionShift;
            final int regionZ = holder.chunkZ >> regionShift;
            final long holderSectionKey = CoordinateUtils.getChunkKey(regionX, regionZ);

            // region may be null
            if (thisRegion != null && this.world.regioniser.getRegionAtUnsynchronised(holder.chunkX, holder.chunkZ) == thisRegion) {
                thisRegionHolders.add(holder);
            } else {
                sectionToUpdates.computeIfAbsent(holderSectionKey, (final long keyInMap) -> {
                    return new ArrayList<>();
                }).add(holder);
            }
        }

        if (!thisRegionHolders.isEmpty()) {
            thisRegion.getData().getHolderManagerRegionData().pendingFullLoadUpdate.addAll(thisRegionHolders);
        }

        if (!sectionToUpdates.isEmpty()) {
            for (final Iterator<Long2ObjectMap.Entry<List<NewChunkHolder>>> iterator = sectionToUpdates.long2ObjectEntrySet().fastIterator();
                 iterator.hasNext();) {
                final Long2ObjectMap.Entry<List<NewChunkHolder>> entry = iterator.next();
                final long sectionKey = entry.getLongKey();

                final int chunkX = CoordinateUtils.getChunkX(sectionKey) << regionShift;
                final int chunkZ = CoordinateUtils.getChunkZ(sectionKey) << regionShift;

                final List<NewChunkHolder> regionHolders = entry.getValue();
                this.taskScheduler.scheduleChunkTaskEventually(chunkX, chunkZ, () -> { // Folia - region threading
                    ChunkHolderManager.this.getCurrentRegionData().pendingFullLoadUpdate.addAll(regionHolders);
                    ChunkHolderManager.this.processPendingFullUpdate();
                }, PrioritisedExecutor.Priority.HIGHEST);
            }
        }
    }

    final ReferenceLinkedOpenHashSet<NewChunkHolder> unloadQueue = new ReferenceLinkedOpenHashSet<>();

    /*
     * Note: Only called on chunk holders that the current ticking region owns
     */
    private void removeChunkHolder(final NewChunkHolder holder) {
        holder.killed = true;
        holder.vanillaChunkHolder.onChunkRemove();
        // Folia - region threading
        ChunkSystem.onChunkHolderDelete(this.world, holder.vanillaChunkHolder);
        this.getCurrentRegionData().autoSaveQueue.remove(holder); // Folia - region threading
        this.chunkHolders.remove(CoordinateUtils.getChunkKey(holder.chunkX, holder.chunkZ));
    }

    // note: never call while inside the chunk system, this will absolutely break everything
    public void processUnloads() {
        TickThread.ensureTickThread("Cannot unload chunks off-main");

        if (BLOCK_TICKET_UPDATES.get() == Boolean.TRUE) {
            throw new IllegalStateException("Cannot unload chunks recursively");
        }
        if (this.ticketLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot hold ticket update lock while calling processUnloads");
        }
        if (this.taskScheduler.schedulingLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot hold scheduling lock while calling processUnloads");
        }

        final ChunkHolderManager.HolderManagerRegionData currentData = this.getCurrentRegionData(); // Folia - region threading

        final List<NewChunkHolder.UnloadState> unloadQueue;
        final List<ChunkProgressionTask> scheduleList = new ArrayList<>();
        this.ticketLock.lock();
        try {
            this.taskScheduler.schedulingLock.lock();
            try {
                if (this.unloadQueue.isEmpty()) {
                    return;
                }
                // in order to ensure all chunks in the unload queue do not have a pending ticket level update,
                // process them now
                this.processTicketUpdates(false, false, scheduleList);

                // Folia start - region threading
                final ArrayDeque<NewChunkHolder> toUnload = new ArrayDeque<>();
                // The unload queue is globally maintained, but we can only unload chunks in our region
                for (final NewChunkHolder holder : this.unloadQueue) {
                    if (TickThread.isTickThreadFor(this.world, holder.chunkX, holder.chunkZ)) {
                        toUnload.add(holder);
                    }
                }
                // Folia end - region threading

                final int unloadCount = Math.max(50, (int)(toUnload.size() * 0.05)); // Folia - region threading
                unloadQueue = new ArrayList<>(unloadCount + 1); // Folia - region threading
                for (int i = 0; i < unloadCount && !toUnload.isEmpty(); ++i) { // Folia - region threading
                    final NewChunkHolder chunkHolder = toUnload.removeFirst(); // Folia - region threading
                    this.unloadQueue.remove(chunkHolder); // Folia - region threading
                    if (chunkHolder.isSafeToUnload() != null) {
                        LOGGER.error("Chunkholder " + chunkHolder + " is not safe to unload but is inside the unload queue?");
                        continue;
                    }
                    final NewChunkHolder.UnloadState state = chunkHolder.unloadStage1();
                    if (state == null) {
                        // can unload immediately
                        this.removeChunkHolder(chunkHolder);
                        continue;
                    }
                    unloadQueue.add(state);
                }
            } finally {
                this.taskScheduler.schedulingLock.unlock();
            }
        } finally {
            this.ticketLock.unlock();
        }
        // schedule tasks, we can't let processTicketUpdates do this because we call it holding the schedule lock
        for (int i = 0, len = scheduleList.size(); i < len; ++i) {
            scheduleList.get(i).schedule();
        }

        final List<NewChunkHolder> toRemove = new ArrayList<>(unloadQueue.size());

        final Boolean before = this.blockTicketUpdates();
        try {
            for (int i = 0, len = unloadQueue.size(); i < len; ++i) {
                final NewChunkHolder.UnloadState state = unloadQueue.get(i);
                final NewChunkHolder holder = state.holder();

                holder.unloadStage2(state);
                toRemove.add(holder);
            }
        } finally {
            this.unblockTicketUpdates(before);
        }

        this.ticketLock.lock();
        try {
            this.taskScheduler.schedulingLock.lock();
            try {
                for (int i = 0, len = toRemove.size(); i < len; ++i) {
                    final NewChunkHolder holder = toRemove.get(i);

                    if (holder.unloadStage3()) {
                        this.removeChunkHolder(holder);
                    } else {
                        // add cooldown so the next unload check is not immediately next tick
                        this.addTicketAtLevel(TicketType.UNLOAD_COOLDOWN, holder.chunkX, holder.chunkZ, MAX_TICKET_LEVEL, Unit.INSTANCE);
                    }
                }
            } finally {
                this.taskScheduler.schedulingLock.unlock();
            }
        } finally {
            this.ticketLock.unlock();
        }
    }

    public enum TicketOperationType {
        ADD, REMOVE, ADD_IF_REMOVED, ADD_AND_REMOVE
    }

    public static record TicketOperation<T, V> (
            TicketOperationType op, long chunkCoord,
            TicketType<T> ticketType, int ticketLevel, T identifier,
            TicketType<V> ticketType2, int ticketLevel2, V identifier2
    ) {

        private TicketOperation(TicketOperationType op, long chunkCoord,
                                TicketType<T> ticketType, int ticketLevel, T identifier) {
            this(op, chunkCoord, ticketType, ticketLevel, identifier, null, 0, null);
        }

        public static <T> TicketOperation<T, T> addOp(final ChunkPos chunk, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return addOp(CoordinateUtils.getChunkKey(chunk), type, ticketLevel, identifier);
        }

        public static <T> TicketOperation<T, T> addOp(final int chunkX, final int chunkZ, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return addOp(CoordinateUtils.getChunkKey(chunkX, chunkZ), type, ticketLevel, identifier);
        }

        public static <T> TicketOperation<T, T> addOp(final long chunk, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return new TicketOperation<>(TicketOperationType.ADD, chunk, type, ticketLevel, identifier);
        }

        public static <T> TicketOperation<T, T> removeOp(final ChunkPos chunk, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return removeOp(CoordinateUtils.getChunkKey(chunk), type, ticketLevel, identifier);
        }

        public static <T> TicketOperation<T, T> removeOp(final int chunkX, final int chunkZ, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return removeOp(CoordinateUtils.getChunkKey(chunkX, chunkZ), type, ticketLevel, identifier);
        }

        public static <T> TicketOperation<T, T> removeOp(final long chunk, final TicketType<T> type, final int ticketLevel, final T identifier) {
            return new TicketOperation<>(TicketOperationType.REMOVE, chunk, type, ticketLevel, identifier);
        }

        public static <T, V> TicketOperation<T, V> addIfRemovedOp(final long chunk,
                                                                  final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                                                  final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
            return new TicketOperation<>(
                    TicketOperationType.ADD_IF_REMOVED, chunk, addType, addLevel, addIdentifier,
                    removeType, removeLevel, removeIdentifier
            );
        }

        public static <T, V> TicketOperation<T, V> addAndRemove(final long chunk,
                                                                final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                                                final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
            return new TicketOperation<>(
                    TicketOperationType.ADD_AND_REMOVE, chunk, addType, addLevel, addIdentifier,
                    removeType, removeLevel, removeIdentifier
            );
        }
    }

    private final MultiThreadedQueue<TicketOperation<?, ?>> delayedTicketUpdates = new MultiThreadedQueue<>();

    // note: MUST hold ticket lock, otherwise operation ordering is lost
    private boolean drainTicketUpdates() {
        boolean ret = false;

        TicketOperation operation;
        while ((operation = this.delayedTicketUpdates.poll()) != null) {
            switch (operation.op) {
                case ADD: {
                    ret |= this.addTicketAtLevel(operation.ticketType, operation.chunkCoord, operation.ticketLevel, operation.identifier);
                    break;
                }
                case REMOVE: {
                    ret |= this.removeTicketAtLevel(operation.ticketType, operation.chunkCoord, operation.ticketLevel, operation.identifier);
                    break;
                }
                case ADD_IF_REMOVED: {
                    ret |= this.addIfRemovedTicket(
                            operation.chunkCoord,
                            operation.ticketType, operation.ticketLevel, operation.identifier,
                            operation.ticketType2, operation.ticketLevel2, operation.identifier2
                    );
                    break;
                }
                case ADD_AND_REMOVE: {
                    ret = true;
                    this.addAndRemoveTickets(
                            operation.chunkCoord,
                            operation.ticketType, operation.ticketLevel, operation.identifier,
                            operation.ticketType2, operation.ticketLevel2, operation.identifier2
                    );
                    break;
                }
            }
        }

        return ret;
    }

    public Boolean tryDrainTicketUpdates() {
        boolean ret = false;
        for (;;) {
            final boolean acquired = this.ticketLock.tryLock();
            try {
                if (!acquired) {
                    return ret ? Boolean.TRUE : null;
                }

                ret |= this.drainTicketUpdates();
            } finally {
                if (acquired) {
                    this.ticketLock.unlock();
                }
            }
            if (this.delayedTicketUpdates.isEmpty()) {
                return Boolean.valueOf(ret);
            } // else: try to re-acquire
        }
    }

    public void pushDelayedTicketUpdate(final TicketOperation<?, ?> operation) {
        this.delayedTicketUpdates.add(operation);
    }

    public void pushDelayedTicketUpdates(final Collection<TicketOperation<?, ?>> operations) {
        this.delayedTicketUpdates.addAll(operations);
    }

    public Boolean tryProcessTicketUpdates() {
        final boolean acquired = this.ticketLock.tryLock();
        try {
            if (!acquired) {
                return null;
            }

            return Boolean.valueOf(this.processTicketUpdates(false, true, null));
        } finally {
            if (acquired) {
                this.ticketLock.unlock();
            }
        }
    }

    private final ThreadLocal<Boolean> BLOCK_TICKET_UPDATES = ThreadLocal.withInitial(() -> {
        return Boolean.FALSE;
    });

    public Boolean blockTicketUpdates() {
        final Boolean ret = BLOCK_TICKET_UPDATES.get();
        BLOCK_TICKET_UPDATES.set(Boolean.TRUE);
        return ret;
    }

    public void unblockTicketUpdates(final Boolean before) {
        BLOCK_TICKET_UPDATES.set(before);
    }

    public boolean processTicketUpdates() {
        return this.processTicketUpdates(true, true, null);
    }

    private static final ThreadLocal<List<ChunkProgressionTask>> CURRENT_TICKET_UPDATE_SCHEDULING = new ThreadLocal<>();

    static List<ChunkProgressionTask> getCurrentTicketUpdateScheduling() {
        return CURRENT_TICKET_UPDATE_SCHEDULING.get();
    }

    private boolean processTicketUpdates(final boolean checkLocks, final boolean processFullUpdates, List<ChunkProgressionTask> scheduledTasks) {
        TickThread.ensureTickThread("Cannot process ticket levels off-main");
        if (BLOCK_TICKET_UPDATES.get() == Boolean.TRUE) {
            throw new IllegalStateException("Cannot update ticket level while unloading chunks or updating entity manager");
        }
        if (checkLocks && this.ticketLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Illegal recursive processTicketUpdates!");
        }
        if (checkLocks && this.taskScheduler.schedulingLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Cannot update ticket levels from a scheduler context!");
        }

        List<NewChunkHolder> changedFullStatus = null;

        final boolean isTickThread = TickThread.isTickThread();

        boolean ret = false;
        final boolean canProcessFullUpdates = processFullUpdates & isTickThread;
        final boolean canProcessScheduling = scheduledTasks == null;

        this.ticketLock.lock();
        try {
            this.drainTicketUpdates();

            final boolean levelsUpdated = this.ticketLevelPropagator.propagateUpdates();
            if (levelsUpdated) {
                // Unlike CB, ticket level updates cannot happen recursively. Thank god.
                if (!this.ticketLevelUpdates.isEmpty()) {
                    ret = true;

                    // first the necessary chunkholders must be created, so just update the ticket levels
                    for (final Iterator<Long2IntMap.Entry> iterator = this.ticketLevelUpdates.long2IntEntrySet().fastIterator(); iterator.hasNext();) {
                        final Long2IntMap.Entry entry = iterator.next();
                        final long key = entry.getLongKey();
                        final int newLevel = entry.getIntValue();

                        NewChunkHolder current = this.chunkHolders.get(key);
                        if (current == null && newLevel > MAX_TICKET_LEVEL) {
                            // not loaded and it shouldn't be loaded!
                            iterator.remove();
                            continue;
                        }

                        final int currentLevel = current == null ? MAX_TICKET_LEVEL + 1 : current.getCurrentTicketLevel();
                        if (currentLevel == newLevel) {
                            // nothing to do
                            iterator.remove();
                            continue;
                        }

                        if (current == null) {
                            // must create
                            current = this.createChunkHolder(key);
                            this.chunkHolders.put(key, current);
                            current.updateTicketLevel(newLevel);
                        } else {
                            current.updateTicketLevel(newLevel);
                        }
                    }

                    if (scheduledTasks == null) {
                        scheduledTasks = new ArrayList<>();
                    }
                    changedFullStatus = new ArrayList<>();

                    // allow the chunkholders to process ticket level updates without needing to acquire the schedule lock every time
                    final List<ChunkProgressionTask> prev = CURRENT_TICKET_UPDATE_SCHEDULING.get();
                    CURRENT_TICKET_UPDATE_SCHEDULING.set(scheduledTasks);
                    try {
                        this.taskScheduler.schedulingLock.lock();
                        try {
                            for (final Iterator<Long2IntMap.Entry> iterator = this.ticketLevelUpdates.long2IntEntrySet().fastIterator(); iterator.hasNext();) {
                                final Long2IntMap.Entry entry = iterator.next();
                                final long key = entry.getLongKey();
                                final NewChunkHolder current = this.chunkHolders.get(key);

                                if (current == null) {
                                    throw new IllegalStateException("Expected chunk holder to be created");
                                }

                                current.processTicketLevelUpdate(scheduledTasks, changedFullStatus);
                            }
                        } finally {
                            this.taskScheduler.schedulingLock.unlock();
                        }
                    } finally {
                        CURRENT_TICKET_UPDATE_SCHEDULING.set(prev);
                    }

                    this.ticketLevelUpdates.clear();

                    // Folia start - region threading
                    // it is possible that a special case new chunk holder had its ticket removed before it was propagated,
                    // which means checkUnload was never invoked. By checking unload here, we ensure that either the
                    // ticket level was propagated (in which case, a later depropagation would check again) or that
                    // we called checkUnload for it.
                    for (final NewChunkHolder special : this.specialCaseUnload) {
                        special.checkUnload();
                    }
                    this.specialCaseUnload.clear();
                    // Folia end - region threading
                }
            }
        } finally {
            this.ticketLock.unlock();
        }

        if (changedFullStatus != null) {
            this.addChangedStatuses(changedFullStatus);
        }

        if (canProcessScheduling && scheduledTasks != null) {
            for (int i = 0, len = scheduledTasks.size(); i < len; ++i) {
                scheduledTasks.get(i).schedule();
            }
        }

        if (canProcessFullUpdates) {
            ret |= this.processPendingFullUpdate();
        }

        return ret;
    }

    // only call on tick thread
    protected final boolean processPendingFullUpdate() {
        final HolderManagerRegionData data = this.getCurrentRegionData();
        if (data == null) {
            return false;
        }

        final ArrayDeque<NewChunkHolder> pendingFullLoadUpdate = data.pendingFullLoadUpdate;

        boolean ret = false;

        List<NewChunkHolder> changedFullStatus = new ArrayList<>();

        NewChunkHolder holder;
        while ((holder = pendingFullLoadUpdate.poll()) != null) {
            ret |= holder.handleFullStatusChange(changedFullStatus);

            if (!changedFullStatus.isEmpty()) {
                this.addChangedStatuses(changedFullStatus);
                changedFullStatus.clear();
            }
        }

        return ret;
    }

    public JsonObject getDebugJsonForWatchdog() {
        // try and detect any potential deadlock that would require us to read unlocked
        try {
            if (this.ticketLock.tryLock(10, TimeUnit.SECONDS)) {
                try {
                    if (this.taskScheduler.schedulingLock.tryLock(10, TimeUnit.SECONDS)) {
                        try {
                            return this.getDebugJsonNoLock();
                        } finally {
                            this.taskScheduler.schedulingLock.unlock();
                        }
                    }
                } finally {
                    this.ticketLock.unlock();
                }
            }
        } catch (final InterruptedException ignore) {}

        LOGGER.error("Failed to acquire ticket and scheduling lock before timeout for world " + this.world.getWorld().getName());

        // because we read without locks, it may throw exceptions for fastutil maps
        // so just try until it works...
        Throwable lastException = null;
        for (int count = 0;count < 1000;++count) {
            try {
                return this.getDebugJsonNoLock();
            } catch (final ThreadDeath death) {
                throw death;
            } catch (final Throwable thr) {
                lastException = thr;
                Thread.yield();
                LockSupport.parkNanos(10_000L);
            }
        }

        // failed, return
        LOGGER.error("Failed to retrieve debug json for watchdog thread without locking", lastException);
        return null;
    }

    private JsonObject getDebugJsonNoLock() {
        final JsonObject ret = new JsonObject();
        // Folia - region threading - move down

        final JsonArray unloadQueue = new JsonArray();
        ret.add("unload_queue", unloadQueue);
        for (final NewChunkHolder holder : this.unloadQueue) {
            final JsonObject coordinate = new JsonObject();
            unloadQueue.add(coordinate);

            coordinate.addProperty("chunkX", Integer.valueOf(holder.chunkX));
            coordinate.addProperty("chunkZ", Integer.valueOf(holder.chunkZ));
        }

        final JsonArray holders = new JsonArray();
        ret.add("chunkholders", holders);

        for (final NewChunkHolder holder : this.getChunkHolders()) {
            holders.add(holder.getDebugJson());
        }

        // Folia start - region threading
        final JsonArray regions = new JsonArray();
        ret.add("regions", regions);
        this.world.regioniser.computeForAllRegionsUnsynchronised((region) -> {
            final JsonObject regionJson = new JsonObject();
            regions.add(regionJson);

            final TickRegions.TickRegionData regionData = region.getData();

            regionJson.addProperty("current_tick", Long.valueOf(regionData.getCurrentTick()));

            final JsonArray removeTickToChunkExpireTicketCount = new JsonArray();
            regionJson.add("remove_tick_to_chunk_expire_ticket_count", removeTickToChunkExpireTicketCount);

            for (final Long2ObjectMap.Entry<Long2IntOpenHashMap> tickEntry : regionData.getHolderManagerRegionData().removeTickToChunkExpireTicketCount.long2ObjectEntrySet()) {
                final long tick = tickEntry.getLongKey();
                final Long2IntOpenHashMap coordinateToCount = tickEntry.getValue();

                final JsonObject tickJson = new JsonObject();
                removeTickToChunkExpireTicketCount.add(tickJson);

                tickJson.addProperty("tick", Long.valueOf(tick));

                final JsonArray tickEntries = new JsonArray();
                tickJson.add("entries", tickEntries);

                for (final Long2IntMap.Entry entry : coordinateToCount.long2IntEntrySet()) {
                    final long coordinate = entry.getLongKey();
                    final int count = entry.getIntValue();

                    final JsonObject entryJson = new JsonObject();
                    tickEntries.add(entryJson);

                    entryJson.addProperty("chunkX", Long.valueOf(CoordinateUtils.getChunkX(coordinate)));
                    entryJson.addProperty("chunkZ", Long.valueOf(CoordinateUtils.getChunkZ(coordinate)));
                    entryJson.addProperty("count", Integer.valueOf(count));
                }
            }

            final JsonArray allTicketsJson = new JsonArray();
            regionJson.add("tickets", allTicketsJson);

            for (final Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> coordinateTickets : regionData.getHolderManagerRegionData().tickets.long2ObjectEntrySet()) {
                final long coordinate = coordinateTickets.getLongKey();
                final SortedArraySet<Ticket<?>> tickets = coordinateTickets.getValue();

                final JsonObject coordinateJson = new JsonObject();
                allTicketsJson.add(coordinateJson);

                coordinateJson.addProperty("chunkX", Long.valueOf(CoordinateUtils.getChunkX(coordinate)));
                coordinateJson.addProperty("chunkZ", Long.valueOf(CoordinateUtils.getChunkZ(coordinate)));

                final JsonArray ticketsSerialized = new JsonArray();
                coordinateJson.add("tickets", ticketsSerialized);

                for (final Ticket<?> ticket : tickets) {
                    final JsonObject ticketSerialized = new JsonObject();
                    ticketsSerialized.add(ticketSerialized);

                    ticketSerialized.addProperty("type", ticket.getType().toString());
                    ticketSerialized.addProperty("level", Integer.valueOf(ticket.getTicketLevel()));
                    ticketSerialized.addProperty("identifier", Objects.toString(ticket.key));
                    ticketSerialized.addProperty("remove_tick", Long.valueOf(ticket.createdTick));
                }
            }
        });
        // Folia end - region threading

        return ret;
    }

    public JsonObject getDebugJson() {
        final List<ChunkProgressionTask> scheduleList = new ArrayList<>();
        try {
            final JsonObject ret;
            this.ticketLock.lock();
            try {
                this.taskScheduler.schedulingLock.lock();
                try {
                    this.processTicketUpdates(false, false, scheduleList);
                    ret = this.getDebugJsonNoLock();
                } finally {
                    this.taskScheduler.schedulingLock.unlock();
                }
            } finally {
                this.ticketLock.unlock();
            }
            return ret;
        } finally {
            // schedule tasks, we can't let processTicketUpdates do this because we call it holding the schedule lock
            for (int i = 0, len = scheduleList.size(); i < len; ++i) {
                scheduleList.get(i).schedule();
            }
        }
    }
}
