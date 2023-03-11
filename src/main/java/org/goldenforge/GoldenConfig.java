package org.goldenforge;

public class GoldenConfig {

    public static int minLoadRadius = 2;
    public static int maxConcurrentSends = 2;
    public static boolean autoconfigSendDistance = true;
    public static double targetPlayerChunkSendRate = 100.0;
    public static double globalMaxChunkSendRate = -1.0;
    public static boolean enableFrustumPriority = false;
    public static double globalMaxChunkLoadRate = -1.0;
    public static double playerMaxConcurrentLoads = 20.0;
    public static double globalMaxConcurrentLoads = 500.0;
    public static double playerMaxChunkLoadRate = -1.0;


    // Misc
    public static boolean preventMovingIntoUnloadedChunks = false;

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

}
