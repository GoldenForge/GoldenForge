package ca.spottedleaf.moonrise.patches.collisions.util;

import net.minecraft.core.*;
import net.minecraft.world.level.block.state.*;

public record FluidOcclusionCacheKey(BlockState first, BlockState second, Direction direction, boolean result) {
}
