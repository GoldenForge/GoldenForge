package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.util.Validate;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Use to manage data that needs to be regionised.
 * <p>
 *     <b>Note:</b> that unlike {@link ThreadLocal}, regionised data is not deleted once the {@code RegionizedData} object is GC'd.
 *     The data is held in reference to the world it resides in.
 * </p>
 * <P>
 *     <b>Note:</b> Keep in mind that when regionised ticking is disabled, the entire server is considered a single region.
 *     That is, the data may or may not cross worlds. As such, the {@code RegionizedData} object must be instanced
 *     per world when appropriate, as it is no longer guaranteed that separate worlds contain separate regions.
 *     See below for more details on instancing per world.
 * </P>
 * <p>
 *     Regionised data may be <b>world-checked</b>. That is, {@link #get()} may throw an exception if the current
 *     region's world does not match the {@code RegionizedData}'s world. Consider the usages of {@code RegionizedData} below
 *     see why the behavior may or may not be desirable:
 *     <pre>
 *         {@code
 *         public class EntityTickList {
 *             private final List<Entity> entities = new ArrayList<>();
 *
 *             public void addEntity(Entity e) {
 *                 this.entities.add(e);
 *             }
 *
 *             public void removeEntity(Entity e) {
 *                 this.entities.remove(e);
 *             }
 *         }
 *
 *         public class World {
 *
 *             // callback is left out of this example
 *             // note: world != null here
 *             public final RegionizedData<EntityTickList> entityTickLists =
 *                 new RegionizedData<>(this, () -> new EntityTickList(), ...);
 *
 *             public void addTickingEntity(Entity e) {
 *                 // What we expect here is that this world is the
 *                 // current ticking region's world.
 *                 // If that is true, then calling this.entityTickLists.get()
 *                 // will retrieve the current region's EntityTickList
 *                 // for this world, which is fine since the current
 *                 // region is contained within this world.
 *
 *                 // But if the current region's world is not this world,
 *                 // and if the world check is disabled, then we will actually
 *                 // retrieve _this_ world's EntityTickList for the region,
 *                 // and NOT the EntityTickList for the region's world.
 *                 // This is because the RegionizedData object is instantiated
 *                 // per world.
 *                 this.entityTickLists.get().addEntity(e);
 *             }
 *         }
 *
 *         public class TickTimes {
 *
 *             private final List<Long> tickTimesNS = new ArrayList<>();
 *
 *             public void completeTick(long timeNS) {
 *                 this.tickTimesNS.add(timeNS);
 *             }
 *
 *             public double getAverageTickLengthMS() {
 *                 double sum = 0.0;
 *                 for (long time : tickTimesNS) {
 *                     sum += (double)time;
 *                 }
 *                 return (sum / this.tickTimesNS.size()) / 1.0E6; // 1ms = 1 million ns
 *             }
 *         }
 *
 *         public class Server {
 *             public final List<World> worlds = ...;
 *
 *             // callback is left out of this example
 *             // note: world == null here, because this RegionizedData object
 *             // is not instantiated per world, but rather globally.
 *             public final RegionizedData<TickTimes> tickTimes =
 *                  new RegionizedData<>(null, () -> new TickTimes(), ...);
 *         }
 *         }
 *     </pre>
 *     In general, it is advised that if a RegionizedData object is instantiated <i>per world</i>, that world checking
 *     is enabled for it by passing the world to the constructor.
 * </p>
 */
public final class RegionizedData<T> {

    private final ServerLevel world;
    private final Supplier<T> initialValueSupplier;
    private final RegioniserCallback<T> callback;

    /**
     * Creates a regionised data holder. The provided initial value supplier may not be null, and it must
     * never produce {@code null} values.
     * <p>
     *     Note that the supplier or regioniser callback may be used while the region lock is held, so any blocking
     *     operations may deadlock the entire server and as such the function should be completely non-blocking
     *     and must complete in a timely manner.
     * </p>
     * <p>
     *     If the provided world is {@code null}, then the world checks are disabled. The world should only ever
     *     be {@code null} if the data is specifically not specific to worlds. For example, using {@code null}
     *     for an entity tick list is invalid since the entities are tied to a world <b>and</b> region,
     *     however using {@code null} for tasks to run at the end of a tick is valid since the tasks are tied to
     *     region <b>only</b>.
     * </p>
     * @param world The world in which the region data resides.
     * @param supplier Initial value supplier used to lazy initialise region data.
     * @param callback Region callback to manage this regionised data.
     */
    public RegionizedData(final ServerLevel world, final Supplier<T> supplier, final RegioniserCallback<T> callback) {
        this.world = world;
        this.initialValueSupplier = Validate.notNull(supplier, "Supplier may not be null.");
        this.callback = Validate.notNull(callback, "Regioniser callback may not be null.");
    }

    T createNewValue() {
        return Validate.notNull(this.initialValueSupplier.get(), "Initial value supplier may not return null");
    }

    RegioniserCallback<T> getCallback() {
        return this.callback;
    }

    /**
     * Returns the current data type for the current ticking region. If there is no region, returns {@code null}.
     * @return the current data type for the current ticking region. If there is no region, returns {@code null}.
     * @throws IllegalStateException If the following are true: The server is in region ticking mode,
     *                               this {@code RegionizedData}'s world is not {@code null},
     *                               and the current ticking region's world does not match this {@code RegionizedData}'s world.
     */
    public @Nullable T get() {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();

        if (region == null) {
            return null;
        }

        if (this.world != null && this.world != region.getData().world) {
            throw new IllegalStateException("World check failed: expected world: " + this.world.getWorld().getName() + ", region world: " + region.getData().world.getWorld().getName());
        }

        return region.getData().getOrCreateRegionizedData(this);
    }

    /**
     * Class responsible for handling merge / split requests from the regioniser.
     * <p>
     *     It is critical to note that each function is called while holding the region lock.
     * </p>
     */
    public static interface RegioniserCallback<T> {

        /**
         * Completely merges the data in {@code from} to {@code into}.
         * <p>
         *     <b>Calculating Tick Offsets:</b>
         *     Sometimes data stores absolute tick deadlines, and since regions tick independently, absolute deadlines
         *     are not comparable across regions. Consider absolute deadlines {@code deadlineFrom, deadlineTo} in
         *     regions {@code from} and {@code into} respectively. We can calculate the relative deadline for the from
         *     region with {@code relFrom = deadlineFrom - currentTickFrom}. Then, we can use the same equation for
         *     computing the absolute deadline in region {@code into} that has the same relative deadline as {@code from}
         *     as {@code deadlineTo = relFrom + currentTickTo}. By substituting {@code relFrom} as {@code deadlineFrom - currentTickFrom},
         *     we finally have that {@code deadlineTo = deadlineFrom + (currentTickTo - currentTickFrom)} and
         *     that we can use an offset {@code fromTickOffset = currentTickTo - currentTickFrom} to calculate
         *     {@code deadlineTo} as {@code deadlineTo = deadlineFrom + fromTickOffset}.
         * </p>
         * <p>
         *     <b>Critical Notes:</b>
         *     <li>
         *         <ul>
         *             This function is called while the region lock is held, so any blocking operations may
         *             deadlock the entire server and as such the function should be completely non-blocking and must complete
         *             in a timely manner.
         *         </ul>
         *         <ul>
         *             This function may not throw any exceptions, or the server will be left in an unrecoverable state.
         *         </ul>
         *     </li>
         * </p>
         *
         * @param from The data to merge from.
         * @param into The data to merge into.
         * @param fromTickOffset The addend to absolute tick deadlines stored in the {@code from} region to adjust to the into region.
         */
        public void merge(final T from, final T into, final long fromTickOffset);

        /**
         * Splits the data in {@code from} into {@code dataSet}.
         * <p>
         *     The chunk coordinate to region section coordinate bit shift amount is provided in {@code chunkToRegionShift}.
         *     To convert from chunk coordinates to region coordinates and keys, see the code below:
         *     <pre>
         *         {@code
         *         int chunkX = ...;
         *         int chunkZ = ...;
         *
         *         int regionSectionX = chunkX >> chunkToRegionShift;
         *         int regionSectionZ = chunkZ >> chunkToRegionShift;
         *         long regionSectionKey = io.papermc.paper.util.CoordinateUtils.getChunkKey(regionSectionX, regionSectionZ);
         *         }
         *     </pre>
         * </p>
         * <p>
         *     The {@code regionToData} hashtable provides a lookup from {@code regionSectionKey} (see above) to the
         *     data that is owned by the region which occupies the region section.
         * </p>
         * <p>
         *     Unlike {@link #merge(Object, Object, long)}, there is no absolute tick offset provided. This is because
         *     the new regions formed from the split will start at the same tick number, and so no adjustment is required.
         * </p>
         *
         * @param from The data to split from.
         * @param chunkToRegionShift The signed right-shift value used to convert chunk coordinates into region section coordinates.
         * @param regionToData Lookup hash table from region section key to .
         * @param dataSet The data set to split into.
         */
        public void split(
            final T from, final int chunkToRegionShift,
            final Long2ReferenceOpenHashMap<T> regionToData, final ReferenceOpenHashSet<T> dataSet
        );
    }
}
