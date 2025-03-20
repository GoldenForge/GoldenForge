package org.goldenforge.mixins.fixes.snowundertrees;

import bl4ckscor3.mod.snowundertrees.SnowUnderTrees;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SnowUnderTrees.class)
public class SnowUnderTreesMixin {

     @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/ModList;isLoaded(Ljava/lang/String;)Z", ordinal = 3))
    public boolean routeToMoonriseLogic(ModList instance, String modTarget) {
        return true;
     }
}
