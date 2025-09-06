package org.goldenforge.mixins.fixes.immersiveportal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.Set;

@Mixin(ServerTeleportationManager.class)
public abstract class ServerTeleportationManagerMixin {

    @Shadow
    @Final
    private Set<Entity> teleportingEntities;

    @Shadow
    public abstract Entity teleportVehicleAcrossDimensions(Entity entity, ResourceKey<Level> toDimension, Vec3 newEyePos);

    @Shadow
    @Final
    private static Logger LOGGER;

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void changePlayerDimension(ServerPlayer player, ServerLevel fromWorld, ServerLevel toWorld, Vec3 newEyePos) {
        this.teleportingEntities.add(player);
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            ((IEServerPlayerEntity)player).ip_stopRidingWithoutTeleportRequest();
        }

        Vec3 oldPos = player.position();
        fromWorld.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
        player.revive();
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        player.setServerLevel(toWorld);
        toWorld.addDuringTeleport(player);
        if (vehicle != null) {
            Vec3 offset = McHelper.getVehicleOffsetFromPassenger(vehicle, player);
            Vec3 vehiclePos = player.position().add(offset);
            vehicle = this.teleportVehicleAcrossDimensions(vehicle, toWorld.dimension(), vehiclePos.add(McHelper.getEyeOffset(vehicle)));
            McHelper.setPosAndLastTickPos(vehicle, player.position().add(offset), McHelper.lastTickPosOf(player).add(offset));
            ((IEServerPlayerEntity)player).ip_startRidingWithoutTeleportRequest(vehicle);
            McHelper.adjustVehicle(player);
        }

        O_O.onPlayerTravelOnServer(player, fromWorld, toWorld);
        ((IEServerPlayerEntity)player).portal_worldChanged(fromWorld, oldPos);
    }

}


