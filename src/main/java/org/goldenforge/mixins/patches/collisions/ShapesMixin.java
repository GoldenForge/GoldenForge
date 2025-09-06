package org.goldenforge.mixins.patches.collisions;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = Shapes.class, priority = 1001)
public class ShapesMixin {

    /**
     * @author manugame_
     * @reason Optimise collisions
     */
    @Overwrite
    public static VoxelShape join(VoxelShape shape1, VoxelShape shape2, BooleanOp function) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.joinOptimized(shape1, shape2, function); // Paper - optimise collisions
    }

    /**
     * @author manugame_
     * @reason Optimise collisions
     */
    @Overwrite
    public static VoxelShape joinUnoptimized(VoxelShape shape1, VoxelShape shape2, BooleanOp function) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.joinUnoptimized(shape1, shape2, function); // Paper - optimise collisions
    }

    /**
     * @author manugame_
     * @reason Optimise collisions
     */
    @Overwrite
    public static boolean joinIsNotEmpty(VoxelShape shape1, VoxelShape shape2, BooleanOp resultOperator) {
        return ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isJoinNonEmpty(shape1, shape2, resultOperator); // Paper - optimise collisions
    }

}
