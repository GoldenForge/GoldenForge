package org.goldenforge.mixins.fixes.immersiveportal;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;

import java.util.concurrent.CompletableFuture;

@Mixin(ImmPtlChunkTickets.class)
public abstract class ImmPtlChunkTicketsMixin {

    @Redirect(method = "lambda$flushThrottling$2", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getEntityTickingChunkFuture()Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<ChunkResult<LevelChunk>> redirect(ChunkHolder instance) {
        return CompletableFuture.completedFuture(ChunkResult.of(instance.getTickingChunk()));

    }
}
