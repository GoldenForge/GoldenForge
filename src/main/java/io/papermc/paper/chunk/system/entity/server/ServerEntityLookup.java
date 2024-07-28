package io.papermc.paper.chunk.system.entity.server;

import com.destroystokyo.paper.util.maplist.ReferenceList;
import io.papermc.paper.chunk.system.entity.EntityLookup;
import io.papermc.paper.world.ChunkEntitySlices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelCallback;

public final class ServerEntityLookup extends EntityLookup {

    private static final Entity[] EMPTY_ENTITY_ARRAY = new Entity[0];

    private final ServerLevel serverWorld;
    public final ReferenceList<Entity> trackerEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY, 0); // Moonrise - entity tracker
    public final ReferenceList<Entity> trackerUnloadedEntities = new ReferenceList<>(EMPTY_ENTITY_ARRAY, 0); // Moonrise - entity tracker

    public ServerEntityLookup(final ServerLevel world, final LevelCallback<Entity> worldCallback) {
        super(world, worldCallback);
        this.serverWorld = world;
    }

    @Override
    protected Boolean blockTicketUpdates() {
        return this.serverWorld.chunkTaskScheduler.chunkHolderManager.blockTicketUpdates();
    }

    @Override
    protected void setBlockTicketUpdates(final Boolean value) {
        this.serverWorld.chunkTaskScheduler.chunkHolderManager.unblockTicketUpdates(value);
    }

    @Override
    protected void checkThread(final int chunkX, final int chunkZ, final String reason) {
        io.papermc.paper.util.TickThread.ensureTickThread(this.serverWorld, chunkX, chunkZ, reason);
    }

    @Override
    protected void checkThread(final Entity entity, final String reason) {
        io.papermc.paper.util.TickThread.ensureTickThread(entity, reason);
    }

    @Override
    protected ChunkEntitySlices createEntityChunk(final int chunkX, final int chunkZ, final boolean transientChunk) {
        // loadInEntityChunk will call addChunk for us
        return  this.serverWorld.chunkTaskScheduler.chunkHolderManager
                .getOrCreateEntityChunk(chunkX, chunkZ, transientChunk);
    }

    @Override
    protected void onEmptySlices(final int chunkX, final int chunkZ) {
        // entity slices unloading is managed by ticket levels in chunk system
    }

    @Override
    protected void entitySectionChangeCallback(final Entity entity,
                                               final int oldSectionX, final int oldSectionY, final int oldSectionZ,
                                               final int newSectionX, final int newSectionY, final int newSectionZ) {
        if (entity instanceof ServerPlayer player) {
            this.serverWorld.moonrise$getNearbyPlayers().tickPlayer(player);
        }
    }

    @Override
    protected void addEntityCallback(final Entity entity) {
        if (entity instanceof ServerPlayer player) {
            this.serverWorld.moonrise$getNearbyPlayers().addPlayer(player);
        }
    }

    @Override
    protected void removeEntityCallback(final Entity entity) {
        if (entity instanceof ServerPlayer player) {
            this.serverWorld.moonrise$getNearbyPlayers().removePlayer(player);
        }
        this.trackerUnloadedEntities.remove(entity); // Moonrise - entity tracker
    }

    @Override
    protected void entityStartLoaded(final Entity entity) {
        // Moonrise start - entity tracker
        this.trackerEntities.add(entity);
        this.trackerUnloadedEntities.remove(entity);
        // Moonrise end - entity tracker
    }

    @Override
    protected void entityEndLoaded(final Entity entity) {
        // Moonrise start - entity tracker
        this.trackerEntities.remove(entity);
        this.trackerUnloadedEntities.add(entity);
        // Moonrise end - entity tracker
    }

    @Override
    protected void entityStartTicking(final Entity entity) {

    }

    @Override
    protected void entityEndTicking(final Entity entity) {

    }
}
