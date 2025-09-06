package org.goldenforge.mixins.fixes.immersiveportal;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

@Mixin(CollisionHelper.class)
public abstract class CollisionhelperMixin {

    @Shadow
    public static AABB getStretchedBoundingBox(Entity entity) {
        return null;
    }

    @Shadow
    public static boolean canCollideWithPortal(Entity entity, Portal portal, float partialTick) {
        return false;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public static void notifyCollidingPortals(Portal portal, float partialTick) {
        if (!portal.isTeleportable()) {
            return;
        }

        AABB portalBoundingBox = portal.getBoundingBox();

        for (Entity entity : portal.level().getEntitiesOfClass(Entity.class, portalBoundingBox.inflate(8.0D))) {
            if (entity instanceof Portal) {
                return;
            }
            AABB entityBoxStretched = getStretchedBoundingBox(entity);
            if (!entityBoxStretched.intersects(portalBoundingBox)) {
                return;
            }
            boolean canCollideWithPortal = canCollideWithPortal(entity, portal, partialTick);
            // use partial tick zero to get the colliding portal before this tick
            if (!canCollideWithPortal) {
                return;
            }

            ((IEEntity) entity).ip_notifyCollidingWithPortal(portal);
        }
    }
}
