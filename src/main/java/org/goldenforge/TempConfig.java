package org.goldenforge;

import net.minecraft.core.Registry;
import net.minecraft.world.entity.EntityType;

public class TempConfig {

    public static boolean dearEnabled = true;
    public static int startDistance = 12;
    public static int startDistanceSquared = startDistance * startDistance;
    public static int maximumActivationPrio = 20;
    public static int activationDistanceMod = 8;

    static {
        for (EntityType<?> entityType : Registry.ENTITY_TYPE) {
            entityType.dabEnabled = true; // reset all, before setting the ones to true
        }
    }
}
