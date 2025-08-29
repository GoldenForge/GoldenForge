package io.papermc.paper.configuration.transformation.world;

import com.mojang.logging.*;
import io.leangen.geantyref.*;
import io.papermc.paper.configuration.*;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.world.level.levelgen.feature.*;
import org.checkerframework.checker.nullness.qual.*;
import org.slf4j.*;
import org.spongepowered.configurate.*;
import org.spongepowered.configurate.transformation.*;

import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.spongepowered.configurate.NodePath.*;

public final class FeatureSeedsGeneration implements TransformAction {

    public static final String FEATURE_SEEDS_KEY = "feature-seeds";
    public static final String GENERATE_KEY = "generate-random-seeds-for-all";
    public static final String FEATURES_KEY = "features";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ResourceLocation worldKey;

    private FeatureSeedsGeneration(ResourceLocation worldKey) {
        this.worldKey = worldKey;
    }

    @Override
    public Object @Nullable [] visitPath(NodePath path, ConfigurationNode value) throws ConfigurateException {
        ConfigurationNode featureNode = value.node(FEATURE_SEEDS_KEY, FEATURES_KEY);
        final Reference2LongMap<Holder<ConfiguredFeature<?, ?>>> features = Objects.requireNonNullElseGet(featureNode.get(new TypeToken<Reference2LongMap<Holder<ConfiguredFeature<?, ?>>>>() {}), Reference2LongOpenHashMap::new);
        final Random random = new SecureRandom();
        AtomicInteger counter = new AtomicInteger(0);
        MinecraftServer.getServer().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).holders().forEach(holder -> {
            if (features.containsKey(holder)) {
                return;
            }

            final long seed = random.nextLong();
            features.put(holder, seed);
            counter.incrementAndGet();
        });
        if (counter.get() > 0) {
            LOGGER.info("Generated {} random feature seeds for {}", counter.get(), this.worldKey);
            featureNode.raw(null);
            featureNode.set(new TypeToken<Reference2LongMap<Holder<ConfiguredFeature<?, ?>>>>() {}, features);
        }
        return null;
    }


    public static void apply(final ConfigurationTransformation.Builder builder, final Configurations.ContextMap contextMap, final ConfigurationNode defaultsNode) {
        if (defaultsNode.node(FEATURE_SEEDS_KEY, GENERATE_KEY).getBoolean(false)) {
            builder.addAction(path(), new FeatureSeedsGeneration(contextMap.require(Configurations.WORLD_KEY)));
        }
    }
}
