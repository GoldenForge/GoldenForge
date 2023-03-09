package com.ishland.c2me.opts.allocs.common;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.Optional;

public class PooledFeatureContext<FC extends FeatureConfiguration> extends FeaturePlaceContext<FC> {

    public static final ThreadLocal<SimpleObjectPool<PooledFeatureContext<?>>> POOL = ThreadLocal.withInitial(() -> new SimpleObjectPool<>(unused -> new PooledFeatureContext<>(), unused -> {}, 2048));

    private Optional<ConfiguredFeature<?, ?>> feature;
    private WorldGenLevel world;
    private ChunkGenerator generator;
    private RandomSource random;
    private BlockPos origin;
    private FC config;

    public PooledFeatureContext() {
        super(null, null, null, null, null, null);
    }

    public void reInit(Optional<ConfiguredFeature<?, ?>> feature, WorldGenLevel world, ChunkGenerator generator, RandomSource random, BlockPos origin, FC config) {
        this.feature = feature;
        this.world = world;
        this.generator = generator;
        this.random = random;
        this.origin = origin;
        this.config = config;
    }

    public void reInit() {
        this.feature = null;
        this.world = null;
        this.generator = null;
        this.random = null;
        this.origin = null;
        this.config = null;
    }

    public WorldGenLevel level() {
        return this.world;
    }

    public ChunkGenerator chunkGenerator() {
        return this.generator;
    }

    public RandomSource random() {
        return this.random;
    }

    public BlockPos origin() {
        return this.origin;
    }

    public FC config() {
        return this.config;
    }

    public Optional<ConfiguredFeature<?, ?>> topFeature() {
        return this.feature;
    }
}
