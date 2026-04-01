package org.goldenforge.mixins.fixes.codechickenlib;

import codechicken.lib.raytracer.MultiIndexedVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiIndexedVoxelShape.class)
public class MultiIndexedVoxelShapeMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/phys/shapes/VoxelShape;Lcom/google/common/collect/ImmutableSet;)V", at = @At("RETURN"))
    public void init(CallbackInfo ci) {
        // GoldenForge: some mods (e.g: copycats+, diagonalblocks) need to initialize the cache after construction
        ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape) this).moonrise$initCache();
    }
}
