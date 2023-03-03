package org.spigotmc;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.AABB;

public class ActivationRange {

    public enum ActivationType
    {
        WATER, // Paper
        FLYING_MONSTER, // Paper
        VILLAGER, // Paper
        MONSTER,
        ANIMAL,
        RAIDER,
        MISC;

        AABB boundingBox = new AABB( 0, 0, 0, 0, 0, 0 );
    }

    public static ActivationType initializeEntityActivationType(Entity entity)
    {
        if (entity instanceof WaterAnimal) { return ActivationType.WATER; } // Paper
        else if (entity instanceof Villager) { return ActivationType.VILLAGER; } // Paper
        else if (entity instanceof FlyingMob && entity instanceof Enemy) { return ActivationType.FLYING_MONSTER; } // Paper - doing & Monster incase Flying no longer includes monster in future
        if ( entity instanceof Raider)
        {
            return ActivationType.RAIDER;
        } else if ( entity instanceof Enemy ) // Paper - correct monster check
        {
            return ActivationType.MONSTER;
        } else if ( entity instanceof PathfinderMob || entity instanceof AmbientCreature)
        {
            return ActivationType.ANIMAL;
        } else
        {
            return ActivationType.MISC;
        }
    }
}
