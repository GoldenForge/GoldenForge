package io.papermc.paper.chunk.system.entity;

import ca.spottedleaf.starlight.common.util.WorldUtil;
import io.papermc.paper.world.ChunkEntitySlices;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelCallback;

public final class DefaultEntityLookup extends EntityLookup {
    public DefaultEntityLookup(final Level world) {
        super(world, new DefaultLevelCallback());
    }

    @Override
    protected Boolean blockTicketUpdates() {
        return null;
    }

    @Override
    protected void setBlockTicketUpdates(final Boolean value) {}

    @Override
    protected void checkThread(final int chunkX, final int chunkZ, final String reason) {}

    @Override
    protected void checkThread(final Entity entity, final String reason) {}

    @Override
    protected ChunkEntitySlices createEntityChunk(final int chunkX, final int chunkZ, final boolean transientChunk) {
        final ChunkEntitySlices ret = new ChunkEntitySlices(
                this.world, chunkX, chunkZ, ChunkHolder.FullChunkStatus.BORDER,
                WorldUtil.getMinSection(this.world), WorldUtil.getMaxSection(this.world)
        );

        // note: not handled by superclass
        this.addChunk(chunkX, chunkZ, ret);

        return ret;
    }

    @Override
    protected void onEmptySlices(final int chunkX, final int chunkZ) {
        this.removeChunk(chunkX, chunkZ);
    }

    @Override
    protected void entitySectionChangeCallback(final Entity entity,
                                               final int oldSectionX, final int oldSectionY, final int oldSectionZ,
                                               final int newSectionX, final int newSectionY, final int newSectionZ) {

    }

    @Override
    protected void addEntityCallback(final Entity entity) {

    }

    @Override
    protected void removeEntityCallback(final Entity entity) {

    }

    @Override
    protected void entityStartLoaded(final Entity entity) {

    }

    @Override
    protected void entityEndLoaded(final Entity entity) {

    }

    @Override
    protected void entityStartTicking(final Entity entity) {

    }

    @Override
    protected void entityEndTicking(final Entity entity) {

    }

    protected static final class DefaultLevelCallback implements LevelCallback<Entity> {

        @Override
        public void onCreated(final Entity entity) {}

        @Override
        public void onDestroyed(final Entity entity) {}

        @Override
        public void onTickingStart(final Entity entity) {}

        @Override
        public void onTickingEnd(final Entity entity) {}

        @Override
        public void onTrackingStart(final Entity entity) {}

        @Override
        public void onTrackingEnd(final Entity entity) {}

        @Override
        public void onSectionChange(final Entity entity) {}
    }
}
