package ca.spottedleaf.moonrise.patches.collisions.shape;

import net.minecraft.world.phys.shapes.*;

public record MergedORCache(
    VoxelShape key,
    VoxelShape result
) {

}
