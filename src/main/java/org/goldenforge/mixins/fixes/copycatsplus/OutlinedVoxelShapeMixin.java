package org.goldenforge.mixins.fixes.copycatsplus;

import com.copycatsplus.copycats.utility.shape.OutlinedVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OutlinedVoxelShape.class)
public class OutlinedVoxelShapeMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    public void init(CallbackInfo ci) {
        // GoldenForge: some mods (e.g: copycats+, diagonalblocks) need to initialize the cache after construction
        ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape) this).moonrise$initCache();
    }
}
