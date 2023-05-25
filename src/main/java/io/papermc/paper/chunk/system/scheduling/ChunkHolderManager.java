package io.papermc.paper.chunk.system.scheduling;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.map.SWMRLong2ObjectHashTable;
import io.papermc.paper.chunk.system.ChunkSystem;
import io.papermc.paper.chunk.system.io.RegionFileIOThread;
import io.papermc.paper.chunk.system.poi.PoiChunk;
import io.papermc.paper.util.CoordinateUtils;
import io.papermc.paper.util.TickThread;
import io.papermc.paper.world.ChunkEntitySlices;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goldenforge.config.GoldenForgeConfig;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

public final class ChunkHolderManager {

    private static final Logger LOGGER = LogManager.getLogger("ChunkHolderManager");

    public static final int FULL_LOADED_TICKET_LEVEL    = 33;
    public static final int BLOCK_TICKING_TICKET_LEVEL  = 32;
    public static final int ENTITY_TICKING_TICKET_LEVEL = 31;
    public static final int MAX_TICKET_LEVEL = ChunkMap.MAX_CHUNK_DISTANCE; // inclusive

    private static final long NO_TIMEOUT_MARKER = -1L;
    private static final long PROBE_MARKER = Long.MIN_VALUE + 1;

    //final ReentrantLock ticketLock = new ReentrantLock();
    public final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock ticketLockArea = new ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock(6);

    private final SWMRLong2ObjectHashTable<NewChunkHolder> chunkHolders = new SWMRLong2ObjectHashTable<>(16384, 0.25f);
    private final java.util.concurrent.ConcurrentHashMap<RegionFileIOThread.ChunkCoordinate, SortedArraySet<Ticket<?>>> tickets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<RegionFileIOThread.ChunkCoordinate, Long2IntOpenHashMap> sectionToChunkToExpireCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final ServerLevel world;
    private final ChunkTaskScheduler taskScheduler;
    private long currentTick;

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

    public ChunkHolderManager(final ServerLevel world, final ChunkTaskScheduler taskScheduler) {
        this.world = world;
        this.taskScheduler = taskScheduler;
        // Folia start - use area based lock to reduce contention
        this.unloadQueue = new org.goldenforge.util.ChunkQueue(4);
        // Folia end - use area based lock to reduce contention
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
        TickThread.ensureTickThread("Closing world off-main");
        if (halt) {
            LOGGER.info("Waiting 60s for chunk system to halt for world '" + this.world.getWorld().getName() + "'");
            if (!this.taskScheduler.halt(true, TimeUnit.SECONDS.toNanos(60L))) {
                LOGGER.warn("Failed to halt world generation/loading tasks for world '" + this.world.getWorld().getName() + "'");
            } else {
                LOGGER.info("Halted chunk system for world '" + this.world.getWorld().getName() + "'");
            }
        }

        if (save) {
            this.saveAllChunks(true, true, true);
        }

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
    }

    void ensureInAutosave(final NewChunkHolder holder) {
        if (!this.autoSaveQueue.contains(holder)) {
            holder.lastAutoSave = MinecraftServer.currentTick;
            this.autoSaveQueue.add(holder);
        }
    }

    public void autoSave() {
        final List<NewChunkHolder> reschedule = new ArrayList<>();
        final long currentTick = MinecraftServer.currentTickLong;
        final long maxSaveTime = currentTick - GoldenForgeConfig.Server.autoSaveInterval.get();
        for (int autoSaved = 0; autoSaved < GoldenForgeConfig.Server.maxAutoSaveChunksPerTick.get() && !this.autoSaveQueue.isEmpty();) {
            final NewChunkHolder holder = this.autoSaveQueue.first();

            if (holder.lastAutoSave > maxSaveTime) {
                break;
            }

            this.autoSaveQueue.remove(holder);

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
                this.autoSaveQueue.add(holder);
            }
        }
    }

    public void saveAllChunks(final boolean flush, final boolean shutdown, final boolean logProgress) {
        final List<NewChunkHolder> holders = this.getChunkHolders();

        if (logProgress) {
            LOGGER.info("Saving all chunkholders for world '" + this.world.getWorld().getName() + "'");
        }

        final DecimalFormat format = new DecimalFormat("#0.00");

        int saved = 0;

        long start = System.nanoTime();
        long lastLog = start;
        boolean needsFlush = false;
        final int flushInterval = 50;

        int savedChunk = 0;
        int savedEntity = 0;
        int savedPoi = 0;

        for (int i = 0, len = holders.size(); i < len; ++i) {
            final NewChunkHolder holder = holders.get(i);
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
        if (flush) {
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

    // Folia start - use area based lock to reduce contention
    protected final io.papermc.paper.threadedregions.ThreadedTicketLevelPropagator ticketLevelPropagator = new io.papermc.paper.threadedregions.ThreadedTicketLevelPropagator() {
        @Override
        protected void processLevelUpdates(final it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap updates) {
            // first the necessary chunkholders must be created, so just update the ticket levels
            for (final Iterator<it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry> iterator = updates.long2ByteEntrySet().fastIterator(); iterator.hasNext();) {
                final it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry = iterator.next();
                final long key = entry.getLongKey();
                final int newLevel = convertBetweenTicketLevels((int)entry.getByteValue());

                NewChunkHolder current = ChunkHolderManager.this.chunkHolders.get(key);
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
                    current = ChunkHolderManager.this.createChunkHolder(key);
                    synchronized (ChunkHolderManager.this.chunkHolders) {
                        ChunkHolderManager.this.chunkHolders.put(key, current);
                    }
                    current.updateTicketLevel(newLevel);
                } else {
                    current.updateTicketLevel(newLevel);
                }
            }
        }

        @Override
        protected void processSchedulingUpdates(final it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap updates, final List<ChunkProgressionTask> scheduledTasks,
                                                final List<NewChunkHolder> changedFullStatus) {
            final List<ChunkProgressionTask> prev = CURRENT_TICKET_UPDATE_SCHEDULING.get();
            CURRENT_TICKET_UPDATE_SCHEDULING.set(scheduledTasks);
            try {
                for (final LongIterator iterator = updates.keySet().iterator(); iterator.hasNext();) {
                    final long key = iterator.nextLong();
                    final NewChunkHolder current = ChunkHolderManager.this.chunkHolders.get(key);

                    if (current == null) {
                        throw new IllegalStateException("Expected chunk holder to be created");
                    }

                    current.processTicketLevelUpdate(scheduledTasks, changedFullStatus);
                }
            } finally {
                CURRENT_TICKET_UPDATE_SCHEDULING.set(prev);
            }
        }
    };
    // Folia end - use area based lock to reduce contention
    // function for converting between ticket levels and propagator levels and vice versa
    // the problem is the ticket level propagator will propagate from a set source down to zero, whereas mojang expects
    // levels to propagate from a set value up to a maximum value. so we need to convert the levels we put into the propagator
    // and the levels we get out of the propagator

    public static int convertBetweenTicketLevels(final int level) {
        return ChunkMap.MAX_CHUNK_DISTANCE - level + 1;
    }

    public boolean hasTickets() {
        return !this.getTicketsCopy().isEmpty();
    }

    public String getTicketDebugString(final long coordinate) {
        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate)); // Folia - use area based lock to reduce contention
        try {
            final SortedArraySet<Ticket<?>> tickets = this.tickets.get(new RegionFileIOThread.ChunkCoordinate(coordinate)); // Folia - use area based lock to reduce contention

            return tickets != null ? tickets.first().toString() : "no_ticket";
        } finally {
            // Folia start - use area based lock to reduce contention
            if (ticketLock != null) {
                this.ticketLockArea.unlock(ticketLock);
            }
            // Folia end - use area based lock to reduce contention
        }
    }

    public SortedArraySet<Ticket<?>> getTicketsSyncronised(long key) {
        synchronized (tickets) {
            return this.tickets.getOrDefault(key, SortedArraySet.create(4));
        }
    }

    public Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> getTicketsCopy() {
        // Folia start - use area based lock to reduce contention
        final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ret = new Long2ObjectOpenHashMap<>();
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<RegionFileIOThread.ChunkCoordinate>> sections = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap();
        final int sectionShift = ChunkTaskScheduler.getChunkSystemLockShift();
        for (final RegionFileIOThread.ChunkCoordinate coord : this.tickets.keySet()) {
            sections.computeIfAbsent(
                    CoordinateUtils.getChunkKey(
                            CoordinateUtils.getChunkX(coord.key) >> sectionShift,
                            CoordinateUtils.getChunkZ(coord.key) >> sectionShift
                    ),
                    (final long keyInMap) -> {
                        return new ArrayList<>();
                    }
            ).add(coord);
        }

        for (final Iterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<List<RegionFileIOThread.ChunkCoordinate>>> iterator = sections.long2ObjectEntrySet().fastIterator();
             iterator.hasNext();) {
            final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<List<RegionFileIOThread.ChunkCoordinate>> entry = iterator.next();
            final long sectionKey = entry.getLongKey();
            final List<RegionFileIOThread.ChunkCoordinate> coordinates = entry.getValue();

            final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(
                    CoordinateUtils.getChunkX(sectionKey) << sectionShift,
                    CoordinateUtils.getChunkZ(sectionKey) << sectionShift
            );
            try {
                for (final RegionFileIOThread.ChunkCoordinate coord : coordinates) {
                    final SortedArraySet<Ticket<?>> tickets = this.tickets.get(coord);
                    if (tickets == null) {
                        // removed before we acquired lock
                        continue;
                    }
                    ret.put(coord.key, new SortedArraySet<>(tickets));
                }
            } finally {
                this.ticketLockArea.unlock(ticketLock);
            }
        }

        return ret;
        // Folia end - use area based lock to reduce contention
    }

    protected final void updateTicketLevel(final long coordinate, final int ticketLevel) {
        if (ticketLevel > ChunkMap.MAX_CHUNK_DISTANCE) {
            this.ticketLevelPropagator.removeSource(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate)); // Folia - use area based lock to reduce contention
        } else {
            this.ticketLevelPropagator.setSource(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate), convertBetweenTicketLevels(ticketLevel)); // Folia - use area based lock to reduce contention
        }
    }

    private static int getTicketLevelAt(SortedArraySet<Ticket<?>> tickets) {
        return !tickets.isEmpty() ? tickets.first().getTicketLevel() : MAX_TICKET_LEVEL + 1;
    }

    public <T> boolean addTicketAtLevel(final TicketType<T> type, final ChunkPos chunkPos, final int level,
                                        final T identifier) {
        return this.addTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkPos), level, identifier);
    }

    // Folia start - use area based lock to reduce contention
    private void addExpireCount(final int chunkX, final int chunkZ) {
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final int sectionShift = 4;
        final RegionFileIOThread.ChunkCoordinate sectionKey = new RegionFileIOThread.ChunkCoordinate(CoordinateUtils.getChunkKey(
                chunkX >> sectionShift,
                chunkZ >> sectionShift
        ));

        this.sectionToChunkToExpireCount.computeIfAbsent(sectionKey, (final RegionFileIOThread.ChunkCoordinate keyInMap) -> {
            return new Long2IntOpenHashMap();
        }).addTo(chunkKey, 1);
    }

    private void removeExpireCount(final int chunkX, final int chunkZ) {
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final int sectionShift = 4;
        final RegionFileIOThread.ChunkCoordinate sectionKey = new RegionFileIOThread.ChunkCoordinate(CoordinateUtils.getChunkKey(
                chunkX >> sectionShift,
                chunkZ >> sectionShift
        ));

        final Long2IntOpenHashMap removeCounts = this.sectionToChunkToExpireCount.get(sectionKey);
        final int prevCount = removeCounts.addTo(chunkKey, -1);

        if (prevCount == 1) {
            removeCounts.remove(chunkKey);
            if (removeCounts.isEmpty()) {
                this.sectionToChunkToExpireCount.remove(sectionKey);
            }
        }
    }
    // Folia end - use area based lock to reduce contention

    public <T> boolean addTicketAtLevel(final TicketType<T> type, final int chunkX, final int chunkZ, final int level,
                                        final T identifier) {
        return this.addTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkX, chunkZ), level, identifier);
    }

    // supposed to return true if the ticket was added and did not replace another
    // but, we always return false if the ticket cannot be added
    public <T> boolean addTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier) {
        // Folia start - use area based lock to reduce contention
        return this.addTicketAtLevel(type, chunk, level, identifier, true);
    }
    <T> boolean addTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier, final boolean lock) {
        final long removeDelay = type.timeout <= 0 ? NO_TIMEOUT_MARKER : type.timeout;
        // Folia end - use area based lock to reduce contention
        if (level > MAX_TICKET_LEVEL) {
            return false;
        }

        // Folia start - use area based lock to reduce contention
        final int chunkX = CoordinateUtils.getChunkX(chunk);
        final int chunkZ = CoordinateUtils.getChunkZ(chunk);
        final RegionFileIOThread.ChunkCoordinate chunkCoord = new RegionFileIOThread.ChunkCoordinate(chunk);
        final Ticket<T> ticket = new Ticket<>(type, level, identifier, removeDelay);

        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = lock ? this.ticketLockArea.lock(chunkX, chunkZ) : null;
        try {
            // Folia end - use area based lock to reduce contention

            final SortedArraySet<Ticket<?>> ticketsAtChunk = this.tickets.computeIfAbsent(chunkCoord, (final RegionFileIOThread.ChunkCoordinate keyInMap) -> { // Folia - region threading // Folia - use area based lock to reduce contention
                return SortedArraySet.create(4);
            });

            final int levelBefore = getTicketLevelAt(ticketsAtChunk);
            final Ticket<T> current = (Ticket<T>)ticketsAtChunk.replace(ticket);
            final int levelAfter = getTicketLevelAt(ticketsAtChunk);

            if (current != ticket) {
                final long oldRemoveDelay = current.createdTick; // Folia - use area based lock to reduce contention
                // Folia start - use area based lock to reduce contention
                if (removeDelay != oldRemoveDelay) {
                    if (oldRemoveDelay != NO_TIMEOUT_MARKER && removeDelay == NO_TIMEOUT_MARKER) {
                        this.removeExpireCount(chunkX, chunkZ);
                    } else if (oldRemoveDelay == NO_TIMEOUT_MARKER) {
                        // since old != new, we have that NO_TIMEOUT_MARKER != new
                        this.addExpireCount(chunkX, chunkZ);
                        // Folia end - use area based lock to reduce contention
                    }
                }
            } else {
                if (removeDelay != NO_TIMEOUT_MARKER) {
                    this.addExpireCount(chunkX, chunkZ); // Folia - use area based lock to reduce contention
                }
            }

            if (levelBefore != levelAfter) {
                this.updateTicketLevel(chunk, levelAfter);
            }

            return current == ticket;
            // Folia - use area based lock to reduce contention
        } finally {
            // Folia start - use area based lock to reduce contention
            if (ticketLock != null) {
                this.ticketLockArea.unlock(ticketLock);
            }
            // Folia end - use area based lock to reduce contention
        }
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final ChunkPos chunkPos, final int level, final T identifier) {
        return this.removeTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkPos), level, identifier);
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final int chunkX, final int chunkZ, final int level, final T identifier) {
        return this.removeTicketAtLevel(type, CoordinateUtils.getChunkKey(chunkX, chunkZ), level, identifier);
    }

    public <T> boolean removeTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier) {
        // Folia start - use area based lock to reduce contention
        return this.removeTicketAtLevel(type, chunk, level, identifier, true);
    }
    <T> boolean removeTicketAtLevel(final TicketType<T> type, final long chunk, final int level, final T identifier, final boolean lock) {
        // Folia end - use area based lock to reduce contention
        if (level > MAX_TICKET_LEVEL) {
            return false;
        }

        // Folia start - use area based lock to reduce contention
        final int chunkX = CoordinateUtils.getChunkX(chunk);
        final int chunkZ = CoordinateUtils.getChunkZ(chunk);
        final RegionFileIOThread.ChunkCoordinate chunkCoord = new RegionFileIOThread.ChunkCoordinate(chunk);
        final Ticket<T> probe = new Ticket<>(type, level, identifier, PROBE_MARKER);

        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = lock ? this.ticketLockArea.lock(chunkX, chunkZ) : null;
        try {
            final SortedArraySet<Ticket<?>> ticketsAtChunk = this.tickets.get(chunkCoord);
            // Folia end - use area based lock to reduce contention
            if (ticketsAtChunk == null) {
                return false;
            }

            final int oldLevel = getTicketLevelAt(ticketsAtChunk);
            final Ticket<T> ticket = (Ticket<T>)ticketsAtChunk.removeAndGet(probe); // Folia - region threading // Folia - use area based lock to reduce contention

            if (ticket == null) {
                return false;
            }

            final int newLevel = getTicketLevelAt(ticketsAtChunk); // Folia - region threading - moved up from below // Folia start - use area based lock to reduce contention
            // Folia start - use area based lock to reduce contention
            // we should not change the ticket levels while the target region may be ticking
            if (oldLevel != newLevel) {
                // we always expect UNKNOWN timeout to be 1, but just in case use max...
                final Ticket<ChunkPos> unknownTicket = new Ticket<>(TicketType.UNKNOWN, level, new ChunkPos(chunk), Math.max(1, TicketType.UNKNOWN.timeout));
                if (ticketsAtChunk.add(unknownTicket)) {
                    this.addExpireCount(chunkX, chunkZ);
                    // Folia end - use area based lock to reduce contention
                } else {
                    throw new IllegalStateException("Should have been able to add " + unknownTicket + " to " + ticketsAtChunk);
                }
            }
            // Folia end - use area based lock to reduce contention
            // Folia end - region threading

            // Folia - use area based lock to reduce contention - not possible anymore

            // Folia - region threading - move up

            // Folia start - use area based lock to reduce contention
            final long removeDelay = ticket.createdTick;
            if (removeDelay != NO_TIMEOUT_MARKER) {
                this.removeExpireCount(chunkX, chunkZ);
                // Folia end - use area based lock to reduce contention
            }

            // Folia - use area based lock to reduce contention - not possible anymore

            return true;
            // Folia - use area based lock to reduce contention
        } finally {
            // Folia start - use area based lock to reduce contention
            if (ticketLock != null) {
                this.ticketLockArea.unlock(ticketLock);
            }
            // Folia end - use area based lock to reduce contention
        }
    }

    // atomic with respect to all add/remove/addandremove ticket calls for the given chunk
    public <T, V> void addAndRemoveTickets(final long chunk, final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                           final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk));  // Folia - use area based lock to reduce contention
        try {
            // Folia start - use area based lock to reduce contention
            this.addTicketAtLevel(addType, chunk, addLevel, addIdentifier, false);
            this.removeTicketAtLevel(removeType, chunk, removeLevel, removeIdentifier, false);
            // Folia end - use area based lock to reduce contention
        } finally {
            this.ticketLockArea.unlock(ticketLock); // Folia - use area based lock to reduce contention
        }
    }

    // atomic with respect to all add/remove/addandremove ticket calls for the given chunk
    public <T, V> boolean addIfRemovedTicket(final long chunk, final TicketType<T> addType, final int addLevel, final T addIdentifier,
                                             final TicketType<V> removeType, final int removeLevel, final V removeIdentifier) {
        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk)); // Folia - use area based lock to reduce contention
        try {
            // Folia start - use area based lock to reduce contention
            if (this.removeTicketAtLevel(removeType, chunk, removeLevel, removeIdentifier, false)) {
                this.addTicketAtLevel(addType, chunk, addLevel, addIdentifier, false);
                // Folia end - use area based lock to reduce contention
                return true;
            }
            return false;
        } finally {
            this.ticketLockArea.unlock(ticketLock); // Folia - use area based lock to reduce contention
        }
    }

//    public void tick() {
//        TickThread.ensureTickThread("Cannot tick ticket manager off-main");
//
//        this.ticketLock.lock();
//        try {
//            final long tick = ++this.currentTick;
//
//            final Long2IntOpenHashMap toRemove = this.removeTickToChunkExpireTicketCount.remove(tick);
//
//            if (toRemove == null) {
//                return;
//            }
//
//            final Predicate<Ticket<?>> expireNow = (final Ticket<?> ticket) -> {
//                return ticket.createdTick == tick;
//            };
//
//            for (final LongIterator iterator = toRemove.keySet().longIterator(); iterator.hasNext();) {
//                final long chunk = iterator.nextLong();
//
//                final SortedArraySet<Ticket<?>> tickets = this.tickets.get(chunk);
//                tickets.removeIf(expireNow);
//                if (tickets.isEmpty()) {
//                    this.tickets.remove(chunk);
//                    this.ticketLevelPropagator.removeSource(chunk);
//                } else {
//                    this.ticketLevelPropagator.setSource(chunk, convertBetweenTicketLevels(tickets.first().getTicketLevel()));
//                }
//            }
//        } finally {
//            this.ticketLock.unlock();
//        }
//
//        this.processTicketUpdates();
//    }

    public void tick() {
        TickThread.ensureTickThread("Cannot tick ticket manager off-main");

        final int sectionShift = 4;

        final Predicate<Ticket<?>> expireNow = (final Ticket<?> ticket) -> {
            if (ticket.createdTick == NO_TIMEOUT_MARKER) {
                return false;
            }
            return --ticket.createdTick <= 0L;
        };


        chunkHolders.forEachValue((ticket) -> {



            final long sectionKey = CoordinateUtils.getChunkKey(ticket.chunkX, ticket.chunkZ);

            final RegionFileIOThread.ChunkCoordinate section = new RegionFileIOThread.ChunkCoordinate(sectionKey);

            if (!this.sectionToChunkToExpireCount.containsKey(section)) {
                return;
            }

            final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(
                    CoordinateUtils.getChunkX(sectionKey) << sectionShift,
                    CoordinateUtils.getChunkZ(sectionKey) << sectionShift
            );

            try {
                final Long2IntOpenHashMap chunkToExpireCount = this.sectionToChunkToExpireCount.get(section);
                if (chunkToExpireCount == null) {
                    // lost to some race
                    return;
                }

                for (final Iterator<Long2IntMap.Entry> iterator1 = chunkToExpireCount.long2IntEntrySet().fastIterator(); iterator1.hasNext();) {
                    final Long2IntMap.Entry entry = iterator1.next();

                    final long chunkKey = entry.getLongKey();
                    final int expireCount = entry.getIntValue();

                    final RegionFileIOThread.ChunkCoordinate chunk = new RegionFileIOThread.ChunkCoordinate(chunkKey);

                    final SortedArraySet<Ticket<?>> tickets = this.tickets.get(chunk);
                    final int levelBefore = getTicketLevelAt(tickets);

                    final int sizeBefore = tickets.size();
                    tickets.removeIf(expireNow);
                    final int sizeAfter = tickets.size();
                    final int levelAfter = getTicketLevelAt(tickets);

                    if (tickets.isEmpty()) {
                        this.tickets.remove(chunk);
                    }
                    if (levelBefore != levelAfter) {
                        this.updateTicketLevel(chunkKey, levelAfter);
                    }

                    final int newExpireCount = expireCount - (sizeBefore - sizeAfter);

                    if (newExpireCount == expireCount) {
                        continue;
                    }

                    if (newExpireCount != 0) {
                        entry.setValue(newExpireCount);
                    } else {
                        iterator1.remove();
                    }
                }

                if (chunkToExpireCount.isEmpty()) {
                    this.sectionToChunkToExpireCount.remove(section);
                }
            } finally {
                this.ticketLockArea.unlock(ticketLock);
            }
        });

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
        final int chunkX = CoordinateUtils.getChunkX(position); final int chunkZ = CoordinateUtils.getChunkZ(position); // Folia - use area based lock to reduce contention
        if (!this.ticketLockArea.isHeldByCurrentThread(chunkX, chunkZ)) { // Folia - use area based lock to reduce contention
            throw new IllegalStateException("Must hold ticket level update lock!");
        }
        if (!this.taskScheduler.schedulingLockArea.isHeldByCurrentThread(chunkX, chunkZ)) { // Folia - use area based lock to reduce contention
            throw new IllegalStateException("Must hold scheduler lock!!");
        }

        // we could just acquire these locks, but...
        // must own the locks because the caller needs to ensure that no unload can occur AFTER this function returns

        NewChunkHolder current = this.chunkHolders.get(position);
        if (current != null) {
            return current;
        }

        current = this.createChunkHolder(position);
        synchronized (this.chunkHolders) { // Folia - use area based lock to reduce contention
            this.chunkHolders.put(position, current);
        } // Folia - use area based lock to reduce contention

        return current;
    }

    private final java.util.concurrent.atomic.AtomicLong entityLoadCounter = new java.util.concurrent.atomic.AtomicLong(); // Folia - use area based lock to reduce contention

    public ChunkEntitySlices getOrCreateEntityChunk(final int chunkX, final int chunkZ, final boolean transientChunk) {
        TickThread.ensureTickThread(this.world, chunkX, chunkZ, "Cannot create entity chunk off-main");
        ChunkEntitySlices ret;

        NewChunkHolder current = this.getChunkHolder(chunkX, chunkZ);
        if (current != null && (ret = current.getEntityChunk()) != null && (transientChunk || !ret.isTransient())) {
            return ret;
        }

        final AtomicBoolean isCompleted = new AtomicBoolean();
        final Thread waiter = Thread.currentThread();
        final Long entityLoadId = Long.valueOf(this.entityLoadCounter.getAndIncrement()); // Folia - use area based lock to reduce contention
        NewChunkHolder.GenericDataLoadTaskCallback loadTask = null;
        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(chunkX, chunkZ); // Folia - use area based lock to reduce contention
        try {
            // Folia - use area based lock to reduce contention
            this.addTicketAtLevel(TicketType.ENTITY_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, entityLoadId);
            final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node schedulingLock = this.taskScheduler.schedulingLockArea.lock(chunkX, chunkZ); // Folia - use area based lock to reduce contention
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
                this.taskScheduler.schedulingLockArea.unlock(schedulingLock); // Folia - use area based lock to reduce contention
            }
        } finally {
            this.ticketLockArea.unlock(ticketLock); // Folia - use area based lock to reduce contention
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

    private final java.util.concurrent.atomic.AtomicLong poiLoadCounter = new java.util.concurrent.atomic.AtomicLong(); // Folia - use area based lock to reduce contention

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
        final Long poiLoadId = Long.valueOf(this.poiLoadCounter.getAndIncrement());
        NewChunkHolder.GenericDataLoadTaskCallback loadTask = null;
        final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(chunkX, chunkZ); // Folia - use area based lock to reduce contention
        try {
            // Folia - use area based lock to reduce contention
            this.addTicketAtLevel(TicketType.POI_LOAD, chunkX, chunkZ, MAX_TICKET_LEVEL, poiLoadId);
            final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node schedulingLock = this.taskScheduler.schedulingLockArea.lock(chunkX, chunkZ); // Folia - use area based lock to reduce contention
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
                this.taskScheduler.schedulingLockArea.unlock(schedulingLock); // Folia - use area based lock to reduce contention
            }
        } finally {
            this.ticketLockArea.unlock(ticketLock); // Folia - use area based lock to reduce contention
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
        if (!TickThread.isTickThread()) {
            this.taskScheduler.scheduleChunkTask(() -> {
                final ArrayDeque<NewChunkHolder> pendingFullLoadUpdate = ChunkHolderManager.this.pendingFullLoadUpdate;
                for (int i = 0, len = changedFullStatus.size(); i < len; ++i) {
                    pendingFullLoadUpdate.add(changedFullStatus.get(i));
                }

                ChunkHolderManager.this.processPendingFullUpdate();
            }, PrioritisedExecutor.Priority.HIGHEST);
        } else {
            final ArrayDeque<NewChunkHolder> pendingFullLoadUpdate = this.pendingFullLoadUpdate;
            for (int i = 0, len = changedFullStatus.size(); i < len; ++i) {
                pendingFullLoadUpdate.add(changedFullStatus.get(i));
            }
        }
    }

    final org.goldenforge.util.ChunkQueue unloadQueue;
    private void removeChunkHolder(final NewChunkHolder holder) {
        holder.killed = true;
        holder.vanillaChunkHolder.onChunkRemove();
        this.autoSaveQueue.remove(holder);
        ChunkSystem.onChunkHolderDelete(this.world, holder.vanillaChunkHolder);
        synchronized (this.chunkHolders) { // Folia - use area based lock to reduce contention
            this.chunkHolders.remove(CoordinateUtils.getChunkKey(holder.chunkX, holder.chunkZ));
        } // Folia - use area based lock to reduce contention
    }

    // note: never call while inside the chunk system, this will absolutely break everything
    public void processUnloads() {
        TickThread.ensureTickThread("Cannot unload chunks off-main");

        if (BLOCK_TICKET_UPDATES.get() == Boolean.TRUE) {
            throw new IllegalStateException("Cannot unload chunks recursively");
        }
        // Folia start - use area based lock to reduce contention
        final int sectionShift = 4;
        final List<org.goldenforge.util.ChunkQueue.SectionToUnload> unloadSectionsForRegion = this.unloadQueue.retrieveForCurrentRegion();
        int unloadCountTentative = 0;
        for (final org.goldenforge.util.ChunkQueue.SectionToUnload sectionRef : unloadSectionsForRegion) {
            final org.goldenforge.util.ChunkQueue.UnloadSection section
                    = this.unloadQueue.getSectionUnsynchronized(sectionRef.sectionX(), sectionRef.sectionZ());

            if (section == null) {
                // removed concurrently
                continue;
            }

            // technically reading the size field is unsafe, and it may be incorrect.
            // We assume that the error here cumulatively goes away over many ticks. If it did not, then it is possible
            // for chunks to never unload or not unload fast enough.
            unloadCountTentative += section.chunks.size();
        }

        if (unloadCountTentative <= 0) {
            // no work to do
            return;
        }

        // Note: The behaviour that we process ticket updates while holding the lock has been dropped here, as it is racey behavior.
        // But, we do need to process updates here so that any add ticket that is synchronised before this call does not go missed.
        this.processTicketUpdates();

        final int toUnloadCount = Math.max(50, (int)(unloadCountTentative * 0.05));
        int processedCount = 0;

        for (final org.goldenforge.util.ChunkQueue.SectionToUnload sectionRef : unloadSectionsForRegion) {
            final List<NewChunkHolder> stage1 = new ArrayList<>();
            final List<NewChunkHolder.UnloadState> stage2 = new ArrayList<>();

            final int sectionLowerX = sectionRef.sectionX() << sectionShift;
            final int sectionLowerZ = sectionRef.sectionZ() << sectionShift;

            // stage 1: set up for stage 2 while holding critical locks
            ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node ticketLock = this.ticketLockArea.lock(sectionLowerX, sectionLowerZ);
            try {
                final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node scheduleLock = this.taskScheduler.schedulingLockArea.lock(sectionLowerX, sectionLowerZ);
                try {
                    final org.goldenforge.util.ChunkQueue.UnloadSection section
                            = this.unloadQueue.getSectionUnsynchronized(sectionRef.sectionX(), sectionRef.sectionZ());

                    if (section == null) {
                        // removed concurrently
                        continue;
                    }

                    // collect the holders to run stage 1 on
                    final int sectionCount = section.chunks.size();

                    if ((sectionCount + processedCount) <= toUnloadCount) {
                        // we can just drain the entire section

                        for (final LongIterator iterator = section.chunks.iterator(); iterator.hasNext();) {
                            final NewChunkHolder holder = this.chunkHolders.get(iterator.nextLong());
                            if (holder == null) {
                                throw new IllegalStateException();
                            }
                            stage1.add(holder);
                        }

                        // remove section
                        this.unloadQueue.removeSection(sectionRef.sectionX(), sectionRef.sectionZ());
                    } else {
                        // processedCount + len = toUnloadCount
                        // we cannot drain the entire section
                        for (int i = 0, len = toUnloadCount - processedCount; i < len; ++i) {
                            final NewChunkHolder holder = this.chunkHolders.get(section.chunks.removeFirstLong());
                            if (holder == null) {
                                throw new IllegalStateException();
                            }
                            stage1.add(holder);
                        }
                    }

                    // run stage 1
                    for (int i = 0, len = stage1.size(); i < len; ++i) {
                        final NewChunkHolder chunkHolder = stage1.get(i);
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
                        stage2.add(state);
                    }
                } finally {
                    this.taskScheduler.schedulingLockArea.unlock(scheduleLock);
                }
            } finally {
                this.ticketLockArea.unlock(ticketLock);
            }

            // stage 2: invoke expensive unload logic, designed to run without locks thanks to stage 1
            final List<NewChunkHolder> stage3 = new ArrayList<>(stage2.size());

            final Boolean before = this.blockTicketUpdates();
            try {
                for (int i = 0, len = stage2.size(); i < len; ++i) {
                    final NewChunkHolder.UnloadState state = stage2.get(i);
                    final NewChunkHolder holder = state.holder();

                    holder.unloadStage2(state);
                    stage3.add(holder);
                }
            } finally {
                this.unblockTicketUpdates(before);
            }

            // stage 3: actually attempt to remove the chunk holders
            ticketLock = this.ticketLockArea.lock(sectionLowerX, sectionLowerZ);
            try {
                final ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock.Node scheduleLock = this.taskScheduler.schedulingLockArea.lock(sectionLowerX, sectionLowerZ);
                try {
                    for (int i = 0, len = stage3.size(); i < len; ++i) {
                        final NewChunkHolder holder = stage3.get(i);

                        if (holder.unloadStage3()) {
                            this.removeChunkHolder(holder);
                        } else {
                            // add cooldown so the next unload check is not immediately next tick
                            this.addTicketAtLevel(TicketType.UNLOAD_COOLDOWN, CoordinateUtils.getChunkKey(holder.chunkX, holder.chunkZ), MAX_TICKET_LEVEL, Unit.INSTANCE, false);
                        }
                    }
                } finally {
                    this.taskScheduler.schedulingLockArea.unlock(scheduleLock);
                }
            } finally {
                this.ticketLockArea.unlock(ticketLock);
            }

            processedCount += stage1.size();

            if (processedCount >= toUnloadCount) {
                break;
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
        // Folia - use area based lock to reduce contention

        List<NewChunkHolder> changedFullStatus = null;

        final boolean isTickThread = TickThread.isTickThread();

        boolean ret = false;
        final boolean canProcessFullUpdates = processFullUpdates & isTickThread;
        final boolean canProcessScheduling = scheduledTasks == null;

        // Folia start - use area based lock to reduce contention
        if (this.ticketLevelPropagator.hasPendingUpdates()) {
            if (scheduledTasks == null) {
                scheduledTasks = new ArrayList<>();
            }
            changedFullStatus = new ArrayList<>();

            ret |= this.ticketLevelPropagator.performUpdates(
                    this.ticketLockArea, this.taskScheduler.schedulingLockArea,
                    scheduledTasks, changedFullStatus
            );
        }
        // Folia end - use area based lock to reduce contention

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
        final ArrayDeque<NewChunkHolder> pendingFullLoadUpdate = this.pendingFullLoadUpdate;

        boolean ret = false;

        List<NewChunkHolder> changedFullStatus = new ArrayList<>();

        NewChunkHolder holder;
        while ((holder = pendingFullLoadUpdate.poll()) != null) {
            ret |= holder.handleFullStatusChange(changedFullStatus);

            if (!changedFullStatus.isEmpty()) {
                for (int i = 0, len = changedFullStatus.size(); i < len; ++i) {
                    pendingFullLoadUpdate.add(changedFullStatus.get(i));
                }
                changedFullStatus.clear();
            }
        }

        return ret;
    }

    public Boolean tryDrainTicketUpdates() {
        return Boolean.FALSE; // Folia start - use area based lock to reduce contention
    }

    public void pushDelayedTicketUpdates(final Collection<TicketOperation<?, ?>> operations) {
        // Folia start - use area based lock to reduce contention
        for (final TicketOperation<?, ?> operation : operations) {
            this.processTicketOp(operation);
        }
        // Folia end - use area based lock to reduce contention
    }

    private boolean processTicketOp(TicketOperation operation) {
        boolean ret = false;
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

        return ret;
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
}
