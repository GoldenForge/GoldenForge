package org.goldenforge.mixins.fixes.theovergrowth;

import com.ldtteam.overgrowth.utils.Utils;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Utils.class)
public class UtilsMixin {

    /**
     * @author manugame_
     * @reason use getChunkIfLoadedImmediately instead of getChunk
     */
    @Overwrite
    public static boolean isChunkLoaded(final LevelAccessor world, final int x, final int z)
    {
        return world.getChunkIfLoadedImmediately(x, z) != null;
    }
}
