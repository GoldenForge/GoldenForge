//package org.goldenforge;
//
//import com.mojang.authlib.GameProfile;
//import net.minecraft.core.BlockPos;
//import net.minecraft.core.Holder;
//import net.minecraft.server.MinecraftServer;
//import net.minecraft.server.level.ServerPlayer;
//import net.minecraft.server.packs.PackResources;
//import net.minecraft.server.packs.PackType;
//import net.minecraft.server.packs.resources.MultiPackResourceManager;
//import net.minecraft.world.effect.MobEffect;
//import net.minecraft.world.entity.Entity;
//import net.minecraft.world.entity.LivingEntity;
//import net.minecraft.world.entity.player.Player;
//import net.minecraft.world.item.ItemStack;
//import net.minecraft.world.level.Level;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.Redirect;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//
//import java.util.List;
//
//@Mixin(ServerPlayer.class)
//public abstract class ServerPlayerMixin extends Player {
//    public ServerPlayerMixin(Level pLevel, BlockPos pPos, float pYRot, GameProfile pGameProfile) {
//        super(pLevel, pPos, pYRot, pGameProfile);
//    }
//
//    @Inject(method = "drop(Z)Z", at = @At(value = "TAIL"))
//    public void restockDrop(boolean p_182295_, CallbackInfoReturnable<Boolean> cir, ItemStack item) {
//        if (!cir.getReturnValue() || item.isEmpty())
//            return;
//
//    }
//}