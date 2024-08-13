package org.spigotmc;

import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;

public class TrackingRange
{

    /**
     * Gets the range an entity should be 'tracked' by players and visible in
     * the client.
     *
     * @param entity
     * @param defaultRange Default range defined by Mojang
     * @return
     */
    public static int getEntityTrackingRange(Entity entity, int defaultRange)
    {
        if ( defaultRange == 0 )
        {
            return defaultRange;
        }
        WorldConfiguration config = entity.level().paperConfig();
        if ( entity instanceof ServerPlayer )
        {
            return config.spigotConfigs.playerTrackingRange;
        // Paper start - Simplify and set water mobs to animal tracking range
        }
        switch (entity.activationType) {
            case RAIDER:
            case MONSTER:
            case FLYING_MONSTER:
                return config.spigotConfigs.monsterTrackingRange;
            case WATER:
            case VILLAGER:
            case ANIMAL:
                return config.spigotConfigs.animalTrackingRange;
            case MISC:
        }
        if ( entity instanceof ItemFrame || entity instanceof Painting || entity instanceof ItemEntity || entity instanceof ExperienceOrb )
        // Paper end
        {
            return config.spigotConfigs.miscTrackingRange;
        } else if ( entity instanceof Display )
        {
            return config.spigotConfigs.displayTrackingRange;
        } else
        {
            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) return ((net.minecraft.server.level.ServerLevel)(entity.getCommandSenderWorld())).getChunkSource().chunkMap.serverViewDistance; // Paper - enderdragon is exempt
            return config.spigotConfigs.otherTrackingRange;
        }
    }
}
