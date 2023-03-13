package com.destroystokyo.paper;

import net.minecraft.world.entity.MobCategory;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PaperConfig {
    public static Map<MobCategory, DespawnRange> despawnRanges = Arrays.stream(MobCategory.values()).collect(Collectors.toMap(Function.identity(), category -> new DespawnRange(category.getNoDespawnDistance(), category.getDespawnDistance())));
    public record DespawnRange(int soft, int hard) {
    }
}
