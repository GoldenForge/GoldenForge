package com.destroystokyo.paper;

public class PaperConfig {


    public static int playerMinChunkLoadRadius;
    public static boolean playerAutoConfigureSendViewDistance;
    public static int playerMaxConcurrentChunkSends;
    public static double playerTargetChunkSendRate;
    public static double globalMaxChunkSendRate;
    public static boolean playerFrustumPrioritisation;
    public static double globalMaxChunkLoadRate;
    public static double playerMaxConcurrentChunkLoads;
    public static double globalMaxConcurrentChunkLoads;
    public static double playerMaxChunkLoadRate;

     static {
        playerMinChunkLoadRadius = 2;
        playerMaxConcurrentChunkSends = 2;
        playerAutoConfigureSendViewDistance = true;
        playerTargetChunkSendRate = 100.0;
        globalMaxChunkSendRate = -1.0;
        playerFrustumPrioritisation = false;
        globalMaxChunkLoadRate = -1.0;
        playerMaxConcurrentChunkLoads = 20.0;
        globalMaxConcurrentChunkLoads = 500.0;
        playerMaxChunkLoadRate = -1.0;
    }

}
