package org.spigotmc;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class ActivationRange
{

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
    // Paper start

    static Activity[] VILLAGER_PANIC_IMMUNITIES = {
            Activity.HIDE,
            Activity.PRE_RAID,
            Activity.RAID,
            Activity.PANIC
    };

    private static int checkInactiveWakeup(Entity entity) {
        Level world = entity.level();
        WorldConfiguration config = world.paperConfig();
        long inactiveFor = MinecraftServer.currentTick - entity.activatedTick;
        if (entity.activationType == ActivationType.VILLAGER) {
            if (inactiveFor > config.spigotConfigs.wakeUpInactiveVillagersEvery && world.wakeupInactiveRemainingVillagers > 0) {
                world.wakeupInactiveRemainingVillagers--;
                return config.spigotConfigs.wakeUpInactiveVillagersFor;
            }
        } else if (entity.activationType == ActivationType.ANIMAL) {
            if (inactiveFor > config.spigotConfigs.wakeUpInactiveAnimalsEvery && world.wakeupInactiveRemainingAnimals > 0) {
                world.wakeupInactiveRemainingAnimals--;
                return config.spigotConfigs.wakeUpInactiveAnimalsFor;
            }
        } else if (entity.activationType == ActivationType.FLYING_MONSTER) {
            if (inactiveFor > config.spigotConfigs.wakeUpInactiveFlyingEvery && world.wakeupInactiveRemainingFlying > 0) {
                world.wakeupInactiveRemainingFlying--;
                return config.spigotConfigs.wakeUpInactiveFlyingFor;
            }
        } else if (entity.activationType == ActivationType.MONSTER || entity.activationType == ActivationType.RAIDER) {
            if (inactiveFor > config.spigotConfigs.wakeUpInactiveMonstersEvery && world.wakeupInactiveRemainingMonsters > 0) {
                world.wakeupInactiveRemainingMonsters--;
                return config.spigotConfigs.wakeUpInactiveMonstersFor;
            }
        }
        return -1;
    }
    // Paper end

    static AABB maxBB = new AABB( 0, 0, 0, 0, 0, 0 );

    /**
     * Initializes an entities type on construction to specify what group this
     * entity is in for activation ranges.
     *
     * @param entity
     * @return group id
     */
    public static ActivationType initializeEntityActivationType(Entity entity)
    {
        if (entity instanceof WaterAnimal) { return ActivationType.WATER; } // Paper
        else if (entity instanceof Villager) { return ActivationType.VILLAGER; } // Paper
        else if (entity instanceof FlyingMob && entity instanceof Enemy) { return ActivationType.FLYING_MONSTER; } // Paper - doing & Monster incase Flying no longer includes monster in future
        if ( entity instanceof Raider )
        {
            return ActivationType.RAIDER;
        } else if ( entity instanceof Enemy ) // Paper - correct monster check
        {
            return ActivationType.MONSTER;
        } else if ( entity instanceof PathfinderMob || entity instanceof AmbientCreature )
        {
            return ActivationType.ANIMAL;
        } else
        {
            return ActivationType.MISC;
        }
    }

    /**
     * These entities are excluded from Activation range checks.
     *
     * @param entity Entity to initialize
     * @param config Spigot config to determine ranges
     * @return boolean If it should always tick.
     */
    public static boolean initializeEntityActivationState(Entity entity, WorldConfiguration config)
    {
        if ( ( entity.activationType == ActivationType.MISC && config.spigotConfigs.miscActivationRange <= 0 )
                || ( entity.activationType == ActivationType.RAIDER && config.spigotConfigs.raiderActivationRange <= 0 )
                || ( entity.activationType == ActivationType.ANIMAL && config.spigotConfigs.animalActivationRange <= 0 )
                || ( entity.activationType == ActivationType.MONSTER && config.spigotConfigs.monsterActivationRange <= 0 )
                || ( entity.activationType == ActivationType.VILLAGER && config.spigotConfigs.villagerActivationRange <= 0 ) // Paper
                || ( entity.activationType == ActivationType.WATER && config.spigotConfigs.waterActivationRange <= 0 ) // Paper
                || ( entity.activationType == ActivationType.FLYING_MONSTER && config.spigotConfigs.flyingMonsterActivationRange <= 0 ) // Paper
                || entity instanceof EyeOfEnder // Paper
                || entity instanceof Player
                || entity instanceof ThrowableProjectile
                || entity instanceof EnderDragon
                || entity instanceof EnderDragonPart
                || entity instanceof WitherBoss
                || entity instanceof AbstractHurtingProjectile
                || entity instanceof LightningBolt
                || entity instanceof PrimedTnt
                || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity // Paper - Always tick falling blocks
                || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart // Paper
                || entity instanceof net.minecraft.world.entity.vehicle.Boat // Paper
                || entity instanceof EndCrystal
                || entity instanceof FireworkRocketEntity
                || entity instanceof ThrownTrident )
        {
            return true;
        }

        return false;
    }

    /**
     * Find what entities are in range of the players in the world and set
     * active if in range.
     *
     * @param world
     */
    public static void activateEntities(Level world)
    {
        final int miscActivationRange = world.paperConfig().spigotConfigs.miscActivationRange;
        final int raiderActivationRange = world.paperConfig().spigotConfigs.raiderActivationRange;
        final int animalActivationRange = world.paperConfig().spigotConfigs.animalActivationRange;
        final int monsterActivationRange = world.paperConfig().spigotConfigs.monsterActivationRange;
        // Paper start
        final int waterActivationRange = world.paperConfig().spigotConfigs.waterActivationRange;
        final int flyingActivationRange = world.paperConfig().spigotConfigs.flyingMonsterActivationRange;
        final int villagerActivationRange = world.paperConfig().spigotConfigs.villagerActivationRange;
        world.wakeupInactiveRemainingAnimals = Math.min(world.wakeupInactiveRemainingAnimals + 1, world.paperConfig().spigotConfigs.wakeUpInactiveAnimals);
        world.wakeupInactiveRemainingVillagers = Math.min(world.wakeupInactiveRemainingVillagers + 1, world.paperConfig().spigotConfigs.wakeUpInactiveVillagers);
        world.wakeupInactiveRemainingMonsters = Math.min(world.wakeupInactiveRemainingMonsters + 1, world.paperConfig().spigotConfigs.wakeUpInactiveMonsters);
        world.wakeupInactiveRemainingFlying = Math.min(world.wakeupInactiveRemainingFlying + 1, world.paperConfig().spigotConfigs.wakeUpInactiveFlying);
        final ServerChunkCache chunkProvider = (ServerChunkCache) world.getChunkSource();
        // Paper end

        int maxRange = Math.max( monsterActivationRange, animalActivationRange );
        maxRange = Math.max( maxRange, raiderActivationRange );
        maxRange = Math.max( maxRange, miscActivationRange );
        // Paper start
        maxRange = Math.max( maxRange, flyingActivationRange );
        maxRange = Math.max( maxRange, waterActivationRange );
        maxRange = Math.max( maxRange, villagerActivationRange );
        // Paper end
        maxRange = Math.min( ( world.getServer().getPlayerList().getSimulationDistance() << 4 ) - 8, maxRange ); //TODO: change simulationdistance getter

        for ( Player player : world.players() )
        {
            player.activatedTick = MinecraftServer.currentTick;
            if ( world.paperConfig().spigotConfigs.ignoreSpectatorActivation && player.isSpectator() )
            {
                continue;
            }

            // Paper start
            int worldHeight = world.getHeight();
            ActivationRange.maxBB = player.getBoundingBox().inflate( maxRange, worldHeight, maxRange );
            ActivationType.MISC.boundingBox = player.getBoundingBox().inflate( miscActivationRange, worldHeight, miscActivationRange );
            ActivationType.RAIDER.boundingBox = player.getBoundingBox().inflate( raiderActivationRange, worldHeight, raiderActivationRange );
            ActivationType.ANIMAL.boundingBox = player.getBoundingBox().inflate( animalActivationRange, worldHeight, animalActivationRange );
            ActivationType.MONSTER.boundingBox = player.getBoundingBox().inflate( monsterActivationRange, worldHeight, monsterActivationRange );
            ActivationType.WATER.boundingBox = player.getBoundingBox().inflate( waterActivationRange, worldHeight, waterActivationRange );
            ActivationType.FLYING_MONSTER.boundingBox = player.getBoundingBox().inflate( flyingActivationRange, worldHeight, flyingActivationRange );
            ActivationType.VILLAGER.boundingBox = player.getBoundingBox().inflate( villagerActivationRange, worldHeight, villagerActivationRange );
            // Paper end

            // Paper start
            java.util.List<Entity> entities = world.getEntities((Entity)null, ActivationRange.maxBB, null);
            boolean tickMarkers = world.paperConfig().entities.markers.tick; // Paper - Configurable marker ticking
            for (Entity entity : entities) {
                // Paper start - Configurable marker ticking
                if (!tickMarkers && entity instanceof net.minecraft.world.entity.Marker) {
                    continue;
                }
                // Paper end - Configurable marker ticking
                ActivationRange.activateEntity(entity);

                // Pufferfish start
                if (GlobalConfiguration.get().dynamicActivationofBrain.enabled && entity.getType().dabEnabled &&
                        (!GlobalConfiguration.get().dynamicActivationofBrain.dontEnableIfInWater || entity.getType().is(net.minecraft.tags.EntityTypeTags.CAN_BREATHE_UNDER_WATER) || !entity.isInWaterOrBubble())) { // Leaf - Option for dontEnableIfInWater
                    if (!entity.activatedPriorityReset) {
                        entity.activatedPriorityReset = true;
                        entity.activatedPriority = GlobalConfiguration.get().dynamicActivationofBrain.maximumActivationPrio;
                    }
                    int squaredDistance = (int) player.distanceToSqr(entity);
                    entity.activatedPriority = squaredDistance >GlobalConfiguration.get().dynamicActivationofBrain.startDistanceSquared ?
                            Math.max(1, Math.min(squaredDistance >> GlobalConfiguration.get().dynamicActivationofBrain.activationDistanceMod, entity.activatedPriority)) :
                            1;
                } else {
                    entity.activatedPriority = 1;
                }
                // Pufferfish end
            }
            // Paper end
        }
    }

    /**
     * Checks for the activation state of all entities in this chunk.
     *
     */
    private static void activateEntity(Entity entity)
    {
        if ( MinecraftServer.currentTick > entity.activatedTick )
        {
            if ( entity.defaultActivationState )
            {
                entity.activatedTick = MinecraftServer.currentTick;
                return;
            }
            if ( entity.activationType.boundingBox.intersects( entity.getBoundingBox() ) )
            {
                entity.activatedTick = MinecraftServer.currentTick;
            }
        }
    }

    /**
     * If an entity is not in range, do some more checks to see if we should
     * give it a shot.
     *
     * @param entity
     * @return
     */
    public static int checkEntityImmunities(final Entity entity) { // return # of ticks to get immunity
        final WorldConfiguration config = entity.level().paperConfig();
        final int inactiveWakeUpImmunity = checkInactiveWakeup(entity);
        if (inactiveWakeUpImmunity > -1) {
            return inactiveWakeUpImmunity;
        }
        if (entity.getRemainingFireTicks() > 0) {
            return 2;
        }
        if (entity.activatedImmunityTick >= MinecraftServer.currentTick) {
            return 1;
        }
        final long inactiveFor = MinecraftServer.currentTick - entity.activatedTick;
        if ((entity.activationType != ActivationType.WATER && entity.isInWater() && entity.isPushedByFluid())) {
            return 100;
        }
        if (!entity.onGround() || entity.getDeltaMovement().horizontalDistanceSqr() > 9.999999747378752E-6D) {
            return 100;
        }
        if (!(entity instanceof final AbstractArrow arrow)) {
            if ((!entity.onGround() && !(entity instanceof FlyingMob))) {
                return 10;
            }
        } else if (!arrow.inGround) {
            return 1;
        }
        // special cases.
        if (entity instanceof final LivingEntity living) {
            if (living.onClimableCached() || living.jumping || living.hurtTime > 0 || !living.activeEffects.isEmpty() || living.isFreezing()) {
                return 1;
            }
            if (entity instanceof final Mob mob && mob.getTarget() != null) {
                return 20;
            }
            if (entity instanceof final Bee bee) {
                final BlockPos movingTarget = bee.getMovingTarget();
                if (bee.isAngry() ||
                        (bee.getHivePos() != null && bee.getHivePos().equals(movingTarget)) ||
                        (bee.getSavedFlowerPos() != null && bee.getSavedFlowerPos().equals(movingTarget))
                ) {
                    return 20;
                }
            }
            if (entity instanceof final Villager villager) {
                final Brain<Villager> behaviorController = villager.getBrain();

                if (config.spigotConfigs.villagersActiveForPanic) {
                    for (final Activity activity : VILLAGER_PANIC_IMMUNITIES) {
                        if (behaviorController.isActive(activity)) {
                            return 20 * 5;
                        }
                    }
                }

                if (config.spigotConfigs.villagersWorkImmunityAfter > 0 && inactiveFor >= config.spigotConfigs.villagersWorkImmunityAfter) {
                    if (behaviorController.isActive(Activity.WORK)) {
                        return config.spigotConfigs.villagersWorkImmunityFor;
                    }
                }
            }
            if (entity instanceof final Llama llama && llama.inCaravan()) {
                return 1;
            }
            if (entity instanceof final Animal animal) {
                if (animal.isBaby() || animal.isInLove()) {
                    return 5;
                }
                if (entity instanceof final Sheep sheep && sheep.isSheared()) {
                    return 1;
                }
            }
            if (entity instanceof final Creeper creeper && creeper.isIgnited()) { // isExplosive
                return 20;
            }
            if (entity instanceof final Mob mob && mob.targetSelector.hasTasks()) {
                return 0;
            }
            if (entity instanceof final Pillager pillager) {
                // TODO:?
            }
        }
        // SPIGOT-6644: Otherwise the target refresh tick will be missed
        if (entity instanceof ExperienceOrb) {
            return 20;
        }
        return -1;
    }

    /**
     * Checks if the entity is active for this tick.
     *
     * @param entity
     * @return
     */
    public static boolean checkIfActive(final Entity entity) {
        // Never safe to skip fireworks or item gravity
        if (entity instanceof FireworkRocketEntity || (entity instanceof ItemEntity && (entity.tickCount + entity.getId()) % 4 == 0)) { // Needed for item gravity, see ItemEntity tick
            return true;
        }
        // special case always immunities
        // immunize brand-new entities, dead entities, and portal scenarios
        if (entity.defaultActivationState || entity.tickCount < 20 * 10 || !entity.isAlive() || (entity.portalProcess != null && !entity.portalProcess.hasExpired()) || entity.portalCooldown > 0) {
            return true;
        }
        // immunize leashed entities
        if (entity instanceof final Mob mob && mob.getLeashHolder() instanceof Player) {
            return true;
        }

        boolean isActive = entity.activatedTick >= MinecraftServer.currentTick;
        entity.isTemporarilyActive = false;

        // Should this entity tick?
        if (!isActive) {
            if ((MinecraftServer.currentTick - entity.activatedTick - 1) % 20 == 0) {
                // Check immunities every 20 ticks.
                final int immunity = checkEntityImmunities(entity);
                if (immunity >= 0) {
                    entity.activatedTick = MinecraftServer.currentTick + immunity;
                } else {
                    entity.isTemporarilyActive = true;
                }
                isActive = true;
            }
        }
        // removed the original's dumb tick skipping for active entities
        return isActive;
    }
}
