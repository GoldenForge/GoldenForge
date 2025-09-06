package org.goldenforge.mixins.fixes.eclipticseasons;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MapChecker.class)
public class MapCheckerMixin {

    /**
     * @author
     * @reason
     */
    @Overwrite
    public static boolean isLoaded(Level level, int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ);
    }
}
