package org.goldenforge;

public class GoldenConfig {

    public static int pageMax = 2560; // TODO this appears to be a duplicate setting with one above
    public static double totalMultiplier = 0.98D; // TODO this should probably be merged into the above inner class

    // Misc
    public static double movedTooQuicklyMultiplier = 10.0D;
    public static double movedWronglyThreshold = 0.0625D;
    public static byte mobSpawnRange = 8;
    public static boolean perPlayerMobSpawns = true;
    // Activation Range

    public static int animalActivationRange = 32;
    public static int monsterActivationRange = 32;
    public static int raiderActivationRange = 48;
    public static int miscActivationRange = 16;
    // Paper start
    public static int flyingMonsterActivationRange = 32;
    public static int waterActivationRange = 16;
    public static int villagerActivationRange = 32;
    public static int wakeUpInactiveAnimals = 4;
    public static int wakeUpInactiveAnimalsEvery = 60*20;
    public static int wakeUpInactiveAnimalsFor = 5*20;
    public static int wakeUpInactiveMonsters = 8;
    public static int wakeUpInactiveMonstersEvery = 20*20;
    public static int wakeUpInactiveMonstersFor = 5*20;
    public static int wakeUpInactiveVillagers = 4;
    public static int wakeUpInactiveVillagersEvery = 30*20;
    public static int wakeUpInactiveVillagersFor = 5*20;
    public static int wakeUpInactiveFlying = 8;
    public static int wakeUpInactiveFlyingEvery = 10*20;
    public static int wakeUpInactiveFlyingFor = 5*20;
    public static int villagersWorkImmunityAfter = 5*20;
    public static int villagersWorkImmunityFor = 20;
    public static boolean villagersActiveForPanic = true;
    // Paper end
    public static boolean tickInactiveVillagers = true;
    public static boolean ignoreSpectatorActivation = false;

    // Tracking range
    public static int playerTrackingRange = 48;
    public static int animalTrackingRange = 48;
    public static int monsterTrackingRange = 48;
    public static int miscTrackingRange = 32;
    public static int otherTrackingRange = 64;

}
