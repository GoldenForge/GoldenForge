package com.destroystokyo.paper;

import net.minecraft.world.entity.MobCategory;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PaperConfig {


    public static int minLoadRadius = 2;
    public static int maxConcurrentSends = 10;
    public static boolean autoconfigSendDistance = true;
    public static double targetPlayerChunkSendRate = 100.0;
    public static double globalMaxChunkSendRate = -1.0;
    public static boolean enableFrustumPriority = false;
    public static double globalMaxChunkLoadRate = -1.0;
    public static double playerMaxConcurrentLoads = 20.0;
    public static double globalMaxConcurrentLoads = 500.0;
    public static double playerMaxChunkLoadRate = -1.0;

    public static Map<MobCategory, DespawnRange> despawnRanges = Arrays.stream(MobCategory.values()).collect(Collectors.toMap(Function.identity(), category -> new DespawnRange(category.getNoDespawnDistance(), category.getDespawnDistance())));

    public record DespawnRange(int soft, int hard) {
    }
}
