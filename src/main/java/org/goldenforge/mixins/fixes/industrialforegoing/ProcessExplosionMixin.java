package org.goldenforge.mixins.fixes.industrialforegoing;

import com.buuz135.industrial.utils.explosion.ProcessExplosion;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ProcessExplosion.class)
public class ProcessExplosionMixin {

    @Redirect(method = "lambda$detonate$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    public boolean executeOnMain(Entity instance, DamageSource p_19946_, float p_19947_) {
        instance.getServer().scheduleOnMain(() -> instance.hurt(p_19946_, p_19947_));
        return false;
    }

}
