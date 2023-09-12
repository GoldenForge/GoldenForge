package io.papermc.paper.util.collisions;

import net.minecraft.world.phys.shapes.VoxelShape;

public record MergedORCache(
    VoxelShape key,
    VoxelShape result
) {

}
