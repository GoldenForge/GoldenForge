package ca.spottedleaf.moonrise.paper;

import ca.spottedleaf.moonrise.common.*;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.paper.util.*;
import com.mojang.datafixers.*;
import com.mojang.serialization.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.*;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.*;
import java.util.function.*;

public final class GoldenForgeHooks extends BaseChunkSystemHooks implements PlatformHooks {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @Override
    public String getBrand() {
        return "GoldenForge";
    }

    @Override
    public int getLightEmission(final BlockState blockState, final BlockGetter world, final BlockPos pos) {
        return blockState.getLightEmission();
    }

    @Override
    public Predicate<BlockState> maybeHasLightEmission() {
        return (final BlockState state) -> {
            return state.hasDynamicLightEmission() || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != 0;
        };
    }

    @Override
    public boolean hasCurrentlyLoadingChunk() {
        return true;
    }

    @Override
    public LevelChunk getCurrentlyLoadingChunk(final GenerationChunkHolder holder) {
        return holder.currentlyLoading;
    }

    @Override
    public void setCurrentlyLoading(final GenerationChunkHolder holder, final LevelChunk levelChunk) {
        holder.currentlyLoading = levelChunk;
    }

    @Override
    public void chunkFullStatusComplete(final LevelChunk newChunk, final ProtoChunk original) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(newChunk, !(original instanceof ImposterProtoChunk)));
    }

    @Override
    public boolean allowAsyncTicketUpdates() {
        return true;
    }

    @Override
    public void onChunkHolderTicketChange(final ServerLevel world, final ChunkHolder holder, final int oldLevel, final int newLevel) {
        final ChunkPos pos = holder.getPos();

        EventHooks.fireChunkTicketLevelUpdated(
                world, CoordinateUtils.getChunkKey(pos.x, pos.z),
                oldLevel, newLevel, holder
        );
    }

    @Override
    public void chunkUnloadFromWorld(final LevelChunk chunk) {
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
    }

    @Override
    public void chunkSyncSave(final ServerLevel world, final ChunkAccess chunk, final CompoundTag data) {
        NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, world, data));
    }

    @Override
    public void onChunkWatch(final ServerLevel world, final LevelChunk chunk, final ServerPlayer player) {
        EventHooks.fireChunkWatch(player, chunk, world);
    }

    @Override
    public void onChunkUnWatch(final ServerLevel world, final ChunkPos chunk, final ServerPlayer player) {
        EventHooks.fireChunkUnWatch(player, chunk, world);
    }

    @Override
    public void addToGetEntities(final Level world, final Entity entity, final AABB boundingBox, final Predicate<? super Entity> predicate, final List<Entity> into) {
        final Collection<PartEntity<?>> parts = world.getPartEntities(); // Goldenforge - adapt to NeoForge's ILevelExtension
        if (parts.isEmpty()) {
            return;
        }

        for (final PartEntity<?> part : parts) { // Goldenforge - adapt to NeoForge's ILevelExtension
            if (part != entity && part.getBoundingBox().intersects(boundingBox) && (predicate == null || predicate.test(part))) {
                into.add(part);
            }
        }
    }

    @Override
    public <T extends Entity> void addToGetEntities(final Level world, final EntityTypeTest<Entity, T> entityTypeTest, final AABB boundingBox, final Predicate<? super T> predicate, final List<? super T> into, final int maxCount) {
        if (into.size() >= maxCount) {
            // fix neoforge issue: do not add if list is already full
            return;
        }

        final Collection<PartEntity<?>> parts = world.getPartEntities(); // Goldenforge - adapt to NeoForge's ILevelExtension
        if (parts.isEmpty()) {
            return;
        }
        for (final PartEntity<?> part : parts) { // Goldenforge - adapt to NeoForge's ILevelExtension
            if (!part.getBoundingBox().intersects(boundingBox)) {
                continue;
            }
            final T casted = (T)entityTypeTest.tryCast(part);
            if (casted != null && (predicate == null || predicate.test(casted))) {
                into.add(casted);
                if (into.size() >= maxCount) {
                    break;
                }
            }
        }
    }

    @Override
    public void entityMove(final Entity entity, final long oldSection, final long newSection) {
        CommonHooks.onEntityEnterSection(entity, oldSection, newSection);
    }

    @Override
    public boolean screenEntity(final ServerLevel world, final Entity entity, final boolean fromDisk, final boolean event) {
        if (event && NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(entity, entity.level(), fromDisk)).isCanceled()) {
            return false;
        }
        return true;
    }

    @Override
    public long[] getCounterTypesUncached(final TicketType type) {
        return type == TicketType.FORCED ? new long[] { ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.COUNTER_TYPE_FORCED } : it.unimi.dsi.fastutil.longs.LongArrays.EMPTY_ARRAY; // Paper - rewrite chunk system
    }

    @Override
    public boolean configFixMC224294() {
        return true;
    }

    @Override
    public boolean configAutoConfigSendDistance() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.autoConfigSendDistance;
    }

    @Override
    public double configPlayerMaxLoadRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkLoadRate;
    }

    @Override
    public double configPlayerMaxGenRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkGenerateRate;
    }

    @Override
    public double configPlayerMaxSendRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate;
    }

    @Override
    public int configPlayerMaxConcurrentLoads() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkLoads;
    }

    @Override
    public int configPlayerMaxConcurrentGens() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates;
    }

    @Override
    public long configAutoSaveInterval(final ServerLevel world) {
        return world.paperConfig().chunks.autoSaveInterval.value();
    }

    @Override
    public int configMaxAutoSavePerTick(final ServerLevel world) {
        return world.paperConfig().chunks.maxAutoSaveChunksPerTick;
    }

    @Override
    public boolean configFixMC159283() {
        return true;
    }

    @Override
    public boolean forceNoSave(final ChunkAccess chunk) {
        return false;
    }

    @Override
    public CompoundTag convertNBT(final DSL.TypeReference type, final DataFixer dataFixer, final CompoundTag nbt,
                                  final int fromVersion, final int toVersion) {
        return (CompoundTag)dataFixer.update(
            type, new Dynamic<>(NbtOps.INSTANCE, nbt), fromVersion, toVersion
        ).getValue();
    }

    @Override
    public boolean hasMainChunkLoadHook() {
        return true;
    }

    @Override
    public void mainChunkLoad(final ChunkAccess chunk, final CompoundTag chunkData) {
        NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, chunkData, chunk instanceof ProtoChunk ? ChunkType.PROTOCHUNK : ChunkType.LEVELCHUNK));
    }

    @Override
    public List<Entity> modifySavedEntities(final ServerLevel world, final int chunkX, final int chunkZ, final List<Entity> entities) {
        return entities;
    }

    @Override
    public void unloadEntity(final Entity entity) {
        entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
    }

    @Override
    public void postLoadProtoChunk(final ServerLevel world, final ProtoChunk chunk) {
        net.minecraft.world.level.chunk.status.ChunkStatusTasks.postLoadProtoChunk(world, chunk.getEntities(), chunk.getPos());

    }

    @Override
    public int modifyEntityTrackingRange(final Entity entity, final int currentRange) {
        return org.spigotmc.TrackingRange.getEntityTrackingRange(entity, currentRange);
    }

    @Override
    public boolean addTicketForEnderPearls(final ServerLevel world) {
        return false;
    }
}