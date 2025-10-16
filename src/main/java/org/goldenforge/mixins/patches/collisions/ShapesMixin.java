package org.goldenforge.mixins.patches.collisions;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
     * Use an inject instead of overwrite to avoid mixin conflicts - obviously this will still disregard any changes made by other
     * mixins, but at least it won't conflict immediately. This is useful because some library mods mixin here when only the content
     * mod actually needs the change.
     *
     * @reason Route to faster logic
     * @author Spottedleaf
     */
    @Inject(
            method = "joinUnoptimized",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void injectJoinUnoptimized(final VoxelShape first, final VoxelShape second, final BooleanOp mergeFunction, final CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(joinUnoptimized(first, second, mergeFunction));
    }

    @Unique
    private static VoxelShape joinUnoptimized(final VoxelShape first, final VoxelShape second, final BooleanOp mergeFunction) {
        final VoxelShape ret = CollisionUtil.joinUnoptimized(first, second, mergeFunction);
        return ret;
    }

    /**
     * @reason Route to faster logic
     * @author Spottedleaf
     * @see #injectJoinUnoptimized(VoxelShape, VoxelShape, BooleanOp, CallbackInfoReturnable)
     */
    @Inject(
            method = "joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void injectJoinIsNotEmpty(final VoxelShape first, final VoxelShape second, final BooleanOp mergeFunction, final CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(joinIsNotEmpty(first, second, mergeFunction));
    }

    @Unique
    private static boolean joinIsNotEmpty(final VoxelShape first, final VoxelShape second, final BooleanOp mergeFunction) {
        final boolean ret = CollisionUtil.isJoinNonEmpty(first, second, mergeFunction);
        return ret;
    }

}
