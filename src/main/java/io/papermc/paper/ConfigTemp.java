package io.papermc.paper;

public class ConfigTemp {

    public static int autoSavePeriod = -1;
    static  {
        autoSavePeriod = -1;
        if (autoSavePeriod > 0) {
            System.out.println("Auto Save Interval: " +autoSavePeriod + " (" + (autoSavePeriod / 20) + "s)");
        } else if (autoSavePeriod < 0) {
            autoSavePeriod = net.minecraft.server.MinecraftServer.getServer().autosavePeriod;
        }
    }

    public static int maxAutoSaveChunksPerTick = 24;

    public static int playerTrackingRange = 48;
    public static int animalTrackingRange = 48;
    public static int monsterTrackingRange = 48;
    public static int miscTrackingRange = 32;
    public static int otherTrackingRange = 64;
}
