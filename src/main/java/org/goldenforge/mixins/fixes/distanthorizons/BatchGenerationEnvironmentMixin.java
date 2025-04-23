package org.goldenforge.mixins.fixes.distanthorizons;

import com.seibel.distanthorizons.core.logging.ConfigBasedLogger;
import loaderCommon.neoforge.com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment;
import loaderCommon.neoforge.com.seibel.distanthorizons.common.wrappers.worldGeneration.GlobalParameters;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Mixin(BatchGenerationEnvironment.class)
public class BatchGenerationEnvironmentMixin {

    @Shadow @Final public GlobalParameters params;

    @Shadow @Final public static ConfigBasedLogger EVENT_LOGGER;

    @Shadow @Final public static ConfigBasedLogger LOAD_LOGGER;

    /**
     * @author
     * @reason
     */
    @Overwrite
    private CompletableFuture<CompoundTag> getChunkNbtDataAsync(ChunkPos chunkPos)
    {
        ServerLevel level = this.params.level;
                return level.getChunkSource().chunkMap.read(chunkPos)
                        .thenApply(optional ->
                        {
                            // Debugging note:
                            // If there are reports of extreme memory use when C2ME is installed, that probably means
                            // this method is queuing a lot of tasks (1,000+), which causes C2ME to explode.

                            //GET_CHUNK_COUNT_REF.decrementAndGet();
                            //PREF_LOGGER.info("chunk getter count ["+F3Screen.NUMBER_FORMAT.format(GET_CHUNK_COUNT_REF.get())+"]");
                            return optional.orElse(null);
                        })
                        .exceptionally((throwable) ->
                        {
                            // unwrap the CompletionException if necessary
                            Throwable actualThrowable = throwable;
                            while (actualThrowable instanceof CompletionException completionException)
                            {
                                actualThrowable = completionException.getCause();
                            }

                            LOAD_LOGGER.warn("DistantHorizons: Couldn't load or make chunk ["+chunkPos+"], error: ["+actualThrowable.getMessage()+"].", actualThrowable);
                            return null;
                        });
            }

}
