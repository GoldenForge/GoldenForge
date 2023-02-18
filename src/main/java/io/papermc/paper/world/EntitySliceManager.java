package io.papermc.paper.world;

import io.papermc.paper.util.CoordinateUtils;
import io.papermc.paper.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;

public final class EntitySliceManager {

    protected static final int REGION_SHIFT = 5;
    protected static final int REGION_MASK = (1 << REGION_SHIFT) - 1;
    protected static final int REGION_SIZE = 1 << REGION_SHIFT;

    public final Level world;

    private final StampedLock stateLock = new StampedLock();
    protected final Long2ObjectOpenHashMap<ChunkSlicesRegion> regions = new Long2ObjectOpenHashMap<>(64, 0.7f);

    private final int minSection; // inclusive
    private final int maxSection; // inclusive

    protected final Long2ObjectOpenHashMap<ChunkHolder.FullChunkStatus> statusMap = new Long2ObjectOpenHashMap<>();
    {
        this.statusMap.defaultReturnValue(ChunkHolder.FullChunkStatus.INACCESSIBLE);
    }

    public EntitySliceManager(final Level world) {
        this.world = world;
        this.minSection = WorldUtil.getMinSection(world);
        this.maxSection = WorldUtil.getMaxSection(world);
    }

    public void chunkStatusChange(final int x, final int z, final ChunkHolder.FullChunkStatus newStatus) {
        if (newStatus == ChunkHolder.FullChunkStatus.INACCESSIBLE) {
            this.statusMap.remove(CoordinateUtils.getChunkKey(x, z));
        } else {
            this.statusMap.put(CoordinateUtils.getChunkKey(x, z), newStatus);
            final ChunkEntitySlices slices = this.getChunk(x, z);
            if (slices != null) {
                slices.updateStatus(newStatus);
            }
        }
    }

    public synchronized void addEntity(final Entity entity) {
        final BlockPos pos = entity.blockPosition();
        final int sectionX = pos.getX() >> 4;
        final int sectionY = Mth.clamp(pos.getY() >> 4, this.minSection, this.maxSection);
        final int sectionZ = pos.getZ() >> 4;
        final ChunkEntitySlices slices = this.getOrCreateChunk(sectionX, sectionZ);
        slices.addEntity(entity, sectionY);

        entity.sectionX = sectionX;
        entity.sectionY = sectionY;
        entity.sectionZ = sectionZ;
    }

    public synchronized void removeEntity(final Entity entity) {
        final ChunkEntitySlices slices = this.getChunk(entity.sectionX, entity.sectionZ);
        slices.removeEntity(entity, entity.sectionY);
        if (slices.isEmpty()) {
            this.removeChunk(entity.sectionX, entity.sectionZ);
        }
    }

    public void moveEntity(final Entity entity) {
        final BlockPos newPos = entity.blockPosition();
        final int newSectionX = newPos.getX() >> 4;
        final int newSectionY = Mth.clamp(newPos.getY() >> 4, this.minSection, this.maxSection);
        final int newSectionZ = newPos.getZ() >> 4;

        if (newSectionX == entity.sectionX && newSectionY == entity.sectionY && newSectionZ == entity.sectionZ) {
            return;
        }

        synchronized (this) {
            // are we changing chunks?
            if (newSectionX != entity.sectionX || newSectionZ != entity.sectionZ) {
                final ChunkEntitySlices slices = this.getOrCreateChunk(newSectionX, newSectionZ);
                final ChunkEntitySlices old = this.getChunk(entity.sectionX, entity.sectionZ);
                synchronized (old) {
                    old.removeEntity(entity, entity.sectionY);
                    if (old.isEmpty()) {
                        this.removeChunk(entity.sectionX, entity.sectionZ);
                    }
                }

                synchronized (slices) {
                    slices.addEntity(entity, newSectionY);

                    entity.sectionX = newSectionX;
                    entity.sectionY = newSectionY;
                    entity.sectionZ = newSectionZ;
                }
            } else {
                final ChunkEntitySlices slices = this.getChunk(newSectionX, newSectionZ);
                // same chunk
                synchronized (slices) {
                    slices.removeEntity(entity, entity.sectionY);
                    slices.addEntity(entity, newSectionY);
                }
                entity.sectionY = newSectionY;
            }
        }

    }

    public void getEntities(final Entity except, final AABB box, final List<Entity> into, final Predicate<? super Entity> predicate) {
        final int minChunkX = (Mth.floor(box.minX) - 2) >> 4;
        final int minChunkZ = (Mth.floor(box.minZ) - 2) >> 4;
        final int maxChunkX = (Mth.floor(box.maxX) + 2) >> 4;
        final int maxChunkZ = (Mth.floor(box.maxZ) + 2) >> 4;

        final int minRegionX = minChunkX >> REGION_SHIFT;
        final int minRegionZ = minChunkZ >> REGION_SHIFT;
        final int maxRegionX = maxChunkX >> REGION_SHIFT;
        final int maxRegionZ = maxChunkZ >> REGION_SHIFT;

        for (int currRegionZ = minRegionZ; currRegionZ <= maxRegionZ; ++currRegionZ) {
            final int minZ = currRegionZ == minRegionZ ? minChunkZ & REGION_MASK : 0;
            final int maxZ = currRegionZ == maxRegionZ ? maxChunkZ & REGION_MASK : REGION_MASK;

            for (int currRegionX = minRegionX; currRegionX <= maxRegionX; ++currRegionX) {
                final ChunkSlicesRegion region = this.getRegion(currRegionX, currRegionZ);

                if (region == null) {
                    continue;
                }

                final int minX = currRegionX == minRegionX ? minChunkX & REGION_MASK : 0;
                final int maxX = currRegionX == maxRegionX ? maxChunkX & REGION_MASK : REGION_MASK;

                for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                    for (int currX = minX; currX <= maxX; ++currX) {
                        final ChunkEntitySlices chunk = region.get(currX | (currZ << REGION_SHIFT));
                        if (chunk == null || !chunk.status.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                            continue;
                        }

                        chunk.getEntities(except, box, into, predicate);
                    }
                }
            }
        }
    }

    public void getHardCollidingEntities(final Entity except, final AABB box, final List<Entity> into, final Predicate<? super Entity> predicate) {
        final int minChunkX = (Mth.floor(box.minX) - 2) >> 4;
        final int minChunkZ = (Mth.floor(box.minZ) - 2) >> 4;
        final int maxChunkX = (Mth.floor(box.maxX) + 2) >> 4;
        final int maxChunkZ = (Mth.floor(box.maxZ) + 2) >> 4;

        final int minRegionX = minChunkX >> REGION_SHIFT;
        final int minRegionZ = minChunkZ >> REGION_SHIFT;
        final int maxRegionX = maxChunkX >> REGION_SHIFT;
        final int maxRegionZ = maxChunkZ >> REGION_SHIFT;

        for (int currRegionZ = minRegionZ; currRegionZ <= maxRegionZ; ++currRegionZ) {
            final int minZ = currRegionZ == minRegionZ ? minChunkZ & REGION_MASK : 0;
            final int maxZ = currRegionZ == maxRegionZ ? maxChunkZ & REGION_MASK : REGION_MASK;

            for (int currRegionX = minRegionX; currRegionX <= maxRegionX; ++currRegionX) {
                final ChunkSlicesRegion region = this.getRegion(currRegionX, currRegionZ);

                if (region == null) {
                    continue;
                }

                final int minX = currRegionX == minRegionX ? minChunkX & REGION_MASK : 0;
                final int maxX = currRegionX == maxRegionX ? maxChunkX & REGION_MASK : REGION_MASK;

                for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                    for (int currX = minX; currX <= maxX; ++currX) {
                        final ChunkEntitySlices chunk = region.get(currX | (currZ << REGION_SHIFT));
                        if (chunk == null || !chunk.status.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                            continue;
                        }

                        chunk.getHardCollidingEntities(except, box, into, predicate);
                    }
                }
            }
        }
    }

    public <T extends Entity> void getEntities(final EntityType<?> type, final AABB box, final List<? super T> into,
                                               final Predicate<? super T> predicate) {
        final int minChunkX = (Mth.floor(box.minX) - 2) >> 4;
        final int minChunkZ = (Mth.floor(box.minZ) - 2) >> 4;
        final int maxChunkX = (Mth.floor(box.maxX) + 2) >> 4;
        final int maxChunkZ = (Mth.floor(box.maxZ) + 2) >> 4;

        final int minRegionX = minChunkX >> REGION_SHIFT;
        final int minRegionZ = minChunkZ >> REGION_SHIFT;
        final int maxRegionX = maxChunkX >> REGION_SHIFT;
        final int maxRegionZ = maxChunkZ >> REGION_SHIFT;

        for (int currRegionZ = minRegionZ; currRegionZ <= maxRegionZ; ++currRegionZ) {
            final int minZ = currRegionZ == minRegionZ ? minChunkZ & REGION_MASK : 0;
            final int maxZ = currRegionZ == maxRegionZ ? maxChunkZ & REGION_MASK : REGION_MASK;

            for (int currRegionX = minRegionX; currRegionX <= maxRegionX; ++currRegionX) {
                final ChunkSlicesRegion region = this.getRegion(currRegionX, currRegionZ);

                if (region == null) {
                    continue;
                }

                final int minX = currRegionX == minRegionX ? minChunkX & REGION_MASK : 0;
                final int maxX = currRegionX == maxRegionX ? maxChunkX & REGION_MASK : REGION_MASK;

                for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                    for (int currX = minX; currX <= maxX; ++currX) {
                        final ChunkEntitySlices chunk = region.get(currX | (currZ << REGION_SHIFT));
                        if (chunk == null || !chunk.status.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                            continue;
                        }

                        chunk.getEntities(type, box, (List)into, (Predicate)predicate);
                    }
                }
            }
        }
    }

    public <T extends Entity> void getEntities(final Class<? extends T> clazz, final Entity except, final AABB box, final List<? super T> into,
                                               final Predicate<? super T> predicate) {
        final int minChunkX = (Mth.floor(box.minX) - 2) >> 4;
        final int minChunkZ = (Mth.floor(box.minZ) - 2) >> 4;
        final int maxChunkX = (Mth.floor(box.maxX) + 2) >> 4;
        final int maxChunkZ = (Mth.floor(box.maxZ) + 2) >> 4;

        final int minRegionX = minChunkX >> REGION_SHIFT;
        final int minRegionZ = minChunkZ >> REGION_SHIFT;
        final int maxRegionX = maxChunkX >> REGION_SHIFT;
        final int maxRegionZ = maxChunkZ >> REGION_SHIFT;

        for (int currRegionZ = minRegionZ; currRegionZ <= maxRegionZ; ++currRegionZ) {
            final int minZ = currRegionZ == minRegionZ ? minChunkZ & REGION_MASK : 0;
            final int maxZ = currRegionZ == maxRegionZ ? maxChunkZ & REGION_MASK : REGION_MASK;

            for (int currRegionX = minRegionX; currRegionX <= maxRegionX; ++currRegionX) {
                final ChunkSlicesRegion region = this.getRegion(currRegionX, currRegionZ);

                if (region == null) {
                    continue;
                }

                final int minX = currRegionX == minRegionX ? minChunkX & REGION_MASK : 0;
                final int maxX = currRegionX == maxRegionX ? maxChunkX & REGION_MASK : REGION_MASK;

                for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                    for (int currX = minX; currX <= maxX; ++currX) {
                        final ChunkEntitySlices chunk = region.get(currX | (currZ << REGION_SHIFT));
                        if (chunk == null || !chunk.status.isOrAfter(ChunkHolder.FullChunkStatus.BORDER)) {
                            continue;
                        }

                        chunk.getEntities(clazz, except, box, into, predicate);
                    }
                }
            }
        }
    }

    public ChunkEntitySlices getChunk(final int chunkX, final int chunkZ) {
        final ChunkSlicesRegion region = this.getRegion(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        if (region == null) {
            return null;
        }

        return region.get((chunkX & REGION_MASK) | ((chunkZ & REGION_MASK) << REGION_SHIFT));
    }

    public ChunkEntitySlices getOrCreateChunk(final int chunkX, final int chunkZ) {
        final ChunkSlicesRegion region = this.getRegion(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        ChunkEntitySlices ret;
        if (region == null || (ret = region.get((chunkX & REGION_MASK) | ((chunkZ & REGION_MASK) << REGION_SHIFT))) == null) {
            ret = new ChunkEntitySlices(this.world, chunkX, chunkZ, this.statusMap.get(CoordinateUtils.getChunkKey(chunkX, chunkZ)),
                WorldUtil.getMinSection(this.world), WorldUtil.getMaxSection(this.world));

            this.addChunk(chunkX, chunkZ, ret);

            return ret;
        }

        return ret;
    }

    public ChunkSlicesRegion getRegion(final int regionX, final int regionZ) {
        final long key = CoordinateUtils.getChunkKey(regionX, regionZ);
        final long attempt = this.stateLock.tryOptimisticRead();
        if (attempt != 0L) {
            try {
                final ChunkSlicesRegion ret = this.regions.get(key);

                if (this.stateLock.validate(attempt)) {
                    return ret;
                }
            } catch (final Error error) {
                throw error;
            } catch (final Throwable thr) {
                // ignore
            }
        }

        this.stateLock.readLock();
        try {
            return this.regions.get(key);
        } finally {
            this.stateLock.tryUnlockRead();
        }
    }

    public synchronized void removeChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        final int relIndex = (chunkX & REGION_MASK) | ((chunkZ & REGION_MASK) << REGION_SHIFT);

        final ChunkSlicesRegion region = this.regions.get(key);
        final int remaining = region.remove(relIndex);

        if (remaining == 0) {
            this.stateLock.writeLock();
            try {
                this.regions.remove(key);
            } finally {
                this.stateLock.tryUnlockWrite();
            }
        }
    }

    public synchronized void addChunk(final int chunkX, final int chunkZ, final ChunkEntitySlices slices) {
        final long key = CoordinateUtils.getChunkKey(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        final int relIndex = (chunkX & REGION_MASK) | ((chunkZ & REGION_MASK) << REGION_SHIFT);

        ChunkSlicesRegion region = this.regions.get(key);
        if (region != null) {
            region.add(relIndex, slices);
        } else {
            region = new ChunkSlicesRegion();
            region.add(relIndex, slices);
            this.stateLock.writeLock();
            try {
                this.regions.put(key, region);
            } finally {
                this.stateLock.tryUnlockWrite();
            }
        }
    }

    public static final class ChunkSlicesRegion {

        protected final ChunkEntitySlices[] slices = new ChunkEntitySlices[REGION_SIZE * REGION_SIZE];
        protected int sliceCount;

        public ChunkEntitySlices get(final int index) {
            return this.slices[index];
        }

        public int remove(final int index) {
            final ChunkEntitySlices slices = this.slices[index];
            if (slices == null) {
                throw new IllegalStateException();
            }

            this.slices[index] = null;

            return --this.sliceCount;
        }

        public void add(final int index, final ChunkEntitySlices slices) {
            final ChunkEntitySlices curr = this.slices[index];
            if (curr != null) {
                throw new IllegalStateException();
            }

            this.slices[index] = slices;

            ++this.sliceCount;
        }
    }
}
