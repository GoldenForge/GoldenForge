package io.papermc.paper.configuration;

import com.google.common.base.Suppliers;
import com.google.common.collect.Table;
import com.mojang.logging.LogUtils;
import io.leangen.geantyref.TypeToken;
import io.papermc.paper.configuration.mapping.InnerClassFieldDiscoverer;
import io.papermc.paper.configuration.serializer.EnumValueSerializer;
import io.papermc.paper.configuration.serializer.NbtPathSerializer;
import io.papermc.paper.configuration.serializer.PacketClassSerializer;
import io.papermc.paper.configuration.serializer.StringRepresentableSerializer;
import io.papermc.paper.configuration.serializer.collections.FastutilMapSerializer;
import io.papermc.paper.configuration.serializer.collections.MapSerializer;
import io.papermc.paper.configuration.serializer.collections.TableSerializer;
import io.papermc.paper.configuration.serializer.registry.RegistryHolderSerializer;
import io.papermc.paper.configuration.serializer.registry.RegistryValueSerializer;
import io.papermc.paper.configuration.transformation.Transformations;
import io.papermc.paper.configuration.transformation.global.versioned.V29_LogIPs;
import io.papermc.paper.configuration.transformation.world.FeatureSeedsGeneration;
import io.papermc.paper.configuration.transformation.world.versioned.V29_ZeroWorldHeight;
import io.papermc.paper.configuration.transformation.world.versioned.V30_RenameFilterNbtFromSpawnEgg;
import io.papermc.paper.configuration.transformation.world.versioned.V31_SpawnLoadedRangeToGameRule;
import io.papermc.paper.configuration.type.BooleanOrDefault;
import io.papermc.paper.configuration.type.Duration;
import io.papermc.paper.configuration.type.DurationOrDisabled;
import io.papermc.paper.configuration.type.EngineMode;
import io.papermc.paper.configuration.type.fallback.FallbackValueSerializer;
import io.papermc.paper.configuration.type.number.DoubleOr;
import io.papermc.paper.configuration.type.number.IntOr;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.core.config.yaml.YamlConfiguration;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.transformation.TransformAction;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import static com.google.common.base.Preconditions.checkState;
import static io.leangen.geantyref.GenericTypeReflector.erase;

@SuppressWarnings("Convert2Diamond")
public class PaperConfigurations extends Configurations<GlobalConfiguration, WorldConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String GLOBAL_CONFIG_FILE_NAME = "paper-global.yml";
    static final String WORLD_DEFAULTS_CONFIG_FILE_NAME = "paper-world-defaults.yml";
    static final String WORLD_CONFIG_FILE_NAME = "paper-world.yml";
    public static final String CONFIG_DIR = "papermc";
    private static final String BACKUP_DIR ="legacy-backup";

    private static final String GLOBAL_HEADER = String.format("""
            This is the global configuration file for Paper.
            As you can see, there's a lot to configure. Some options may impact gameplay, so use
            with caution, and make sure you know what each option does before configuring.

            If you need help with the configuration or have any questions related to Paper,
            join us in our Discord or check the docs page.

            The world configuration options have been moved inside
            their respective world folder. The files are named %s

            Docs: https://docs.papermc.io/
            Discord: https://discord.gg/papermc
            Website: https://papermc.io/""", WORLD_CONFIG_FILE_NAME);

    private static final String WORLD_DEFAULTS_HEADER = """
            This is the world defaults configuration file for Paper.
            As you can see, there's a lot to configure. Some options may impact gameplay, so use
            with caution, and make sure you know what each option does before configuring.

            If you need help with the configuration or have any questions related to Paper,
            join us in our Discord or check the docs page.

            Configuration options here apply to all worlds, unless you specify overrides inside
            the world-specific config file inside each world folder.

            Docs: https://docs.papermc.io/
            Discord: https://discord.gg/papermc
            Website: https://papermc.io/""";

    private static final Function<ContextMap, String> WORLD_HEADER = map -> String.format("""
        This is a world configuration file for Paper.
        This file may start empty but can be filled with settings to override ones in the %s/%s
        
        World: %s (%s)""",
            PaperConfigurations.CONFIG_DIR,
            PaperConfigurations.WORLD_DEFAULTS_CONFIG_FILE_NAME,
            map.require(WORLD_NAME),
            map.require(WORLD_KEY)
    );

    private static final String MOVED_NOTICE = """
        The global and world default configuration files have moved to %s
        and the world-specific configuration file has been moved inside
        the respective world folder.
        
        See https://docs.papermc.io/paper/configuration for more information.
        """;


    public PaperConfigurations(final Path globalFolder) {
        super(globalFolder, GlobalConfiguration.class, WorldConfiguration.class, GLOBAL_CONFIG_FILE_NAME, WORLD_DEFAULTS_CONFIG_FILE_NAME, WORLD_CONFIG_FILE_NAME);
    }

    @Override
    protected int globalConfigVersion() {
        return GlobalConfiguration.CURRENT_VERSION;
    }

    @Override
    protected int worldConfigVersion() {
        return WorldConfiguration.CURRENT_VERSION;
    }

    @Override
    protected YamlConfigurationLoader.Builder createLoaderBuilder() {
        return super.createLoaderBuilder()
                .defaultOptions(PaperConfigurations::defaultOptions);
    }

    private static ConfigurationOptions defaultOptions(ConfigurationOptions options) {
        return options.serializers(builder -> builder
                .register(MapSerializer.TYPE, new MapSerializer(false))
                .register(new EnumValueSerializer())
                .register(IntOr.Default.SERIALIZER)
                .register(IntOr.Disabled.SERIALIZER)
                .register(DoubleOr.Default.SERIALIZER)
                .register(DoubleOr.Disabled.SERIALIZER)
                .register(BooleanOrDefault.SERIALIZER)
                .register(Duration.SERIALIZER)
                .register(DurationOrDisabled.SERIALIZER)
                .register(NbtPathSerializer.SERIALIZER)
        );
    }

    @Override
    protected ObjectMapper.Factory.Builder createGlobalObjectMapperFactoryBuilder() {
        return defaultGlobalFactoryBuilder(super.createGlobalObjectMapperFactoryBuilder());
    }

    private static ObjectMapper.Factory.Builder defaultGlobalFactoryBuilder(ObjectMapper.Factory.Builder builder) {
        return builder.addDiscoverer(InnerClassFieldDiscoverer.globalConfig());
    }

    @Override
    protected YamlConfigurationLoader.Builder createGlobalLoaderBuilder() {
        return super.createGlobalLoaderBuilder()
                .defaultOptions(PaperConfigurations::defaultGlobalOptions);
    }

    private static ConfigurationOptions defaultGlobalOptions(ConfigurationOptions options) {
        return options
                .header(GLOBAL_HEADER)
                .serializers(builder -> builder
                        .register(new PacketClassSerializer())
                );
    }

    @Override
    public GlobalConfiguration initializeGlobalConfiguration(final RegistryAccess registryAccess) throws ConfigurateException {
        GlobalConfiguration configuration = super.initializeGlobalConfiguration(registryAccess);
        GlobalConfiguration.set(configuration);
        return configuration;
    }

    @Override
    protected ContextMap.Builder createDefaultContextMap(final RegistryAccess registryAccess) {
        return super.createDefaultContextMap(registryAccess);
    }

    @Override
    protected ObjectMapper.Factory.Builder createWorldObjectMapperFactoryBuilder(final ContextMap contextMap) {
        return super.createWorldObjectMapperFactoryBuilder(contextMap)
                .addNodeResolver(new NestedSetting.Factory())
                .addDiscoverer(InnerClassFieldDiscoverer.worldConfig(createWorldConfigInstance(contextMap)));
    }

    private static WorldConfiguration createWorldConfigInstance(ContextMap contextMap) {
        return new WorldConfiguration(
                contextMap.require(Configurations.WORLD_KEY)
        );
    }

    @Override
    protected YamlConfigurationLoader.Builder createWorldConfigLoaderBuilder(final ContextMap contextMap) {
        final RegistryAccess access = contextMap.require(REGISTRY_ACCESS);
        return super.createWorldConfigLoaderBuilder(contextMap)
                .defaultOptions(options -> options
                        .header(contextMap.require(WORLD_NAME).equals(WORLD_DEFAULTS) ? WORLD_DEFAULTS_HEADER : WORLD_HEADER.apply(contextMap))
                        .serializers(serializers -> serializers
                                .register(new TypeToken<Reference2IntMap<?>>() {}, new FastutilMapSerializer.SomethingToPrimitive<Reference2IntMap<?>>(Reference2IntOpenHashMap::new, Integer.TYPE))
                                .register(new TypeToken<Reference2LongMap<?>>() {}, new FastutilMapSerializer.SomethingToPrimitive<Reference2LongMap<?>>(Reference2LongOpenHashMap::new, Long.TYPE))
                                .register(new TypeToken<Table<?, ?, ?>>() {}, new TableSerializer())
                                .register(StringRepresentableSerializer::isValidFor, new StringRepresentableSerializer())
                                .register(EngineMode.SERIALIZER)
                                .register(FallbackValueSerializer.create(MinecraftServer::getServer))
                                .register(new RegistryValueSerializer<>(new TypeToken<EntityType<?>>() {}, access, Registries.ENTITY_TYPE, true))
                                .register(new RegistryValueSerializer<>(Item.class, access, Registries.ITEM, true))
                                .register(new RegistryValueSerializer<>(Block.class, access, Registries.BLOCK, true))
                                .register(new RegistryHolderSerializer<>(new TypeToken<ConfiguredFeature<?, ?>>() {}, access, Registries.CONFIGURED_FEATURE, false))
                        )
                );
    }

    @Override
    protected void applyWorldConfigTransformations(final ContextMap contextMap, final ConfigurationNode node, final @Nullable ConfigurationNode defaultsNode) throws ConfigurateException {
        final ConfigurationTransformation.Builder builder = ConfigurationTransformation.builder();
        for (final NodePath path : RemovedConfigurations.REMOVED_WORLD_PATHS) {
            builder.addAction(path, TransformAction.remove());
        }
        builder.build().apply(node);

        final ConfigurationTransformation.VersionedBuilder versionedBuilder = Transformations.versionedBuilder();
        V29_ZeroWorldHeight.apply(versionedBuilder);
        V30_RenameFilterNbtFromSpawnEgg.apply(versionedBuilder);
        V31_SpawnLoadedRangeToGameRule.apply(versionedBuilder, contextMap, defaultsNode);
        // ADD FUTURE VERSIONED TRANSFORMS TO versionedBuilder HERE
        versionedBuilder.build().apply(node);
    }

    @Override
    protected void applyGlobalConfigTransformations(ConfigurationNode node) throws ConfigurateException {
        ConfigurationTransformation.Builder builder = ConfigurationTransformation.builder();
        for (NodePath path : RemovedConfigurations.REMOVED_GLOBAL_PATHS) {
            builder.addAction(path, TransformAction.remove());
        }
        builder.build().apply(node);

        final ConfigurationTransformation.VersionedBuilder versionedBuilder = Transformations.versionedBuilder();
        V29_LogIPs.apply(versionedBuilder);
        // ADD FUTURE VERSIONED TRANSFORMS TO versionedBuilder HERE
        versionedBuilder.build().apply(node);
    }

    private static final List<Transformations.DefaultsAware> DEFAULT_AWARE_TRANSFORMATIONS = List.of(
            FeatureSeedsGeneration::apply
    );

    @Override
    protected void applyDefaultsAwareWorldConfigTransformations(final ContextMap contextMap, final ConfigurationNode worldNode, final ConfigurationNode defaultsNode) throws ConfigurateException {
        final ConfigurationTransformation.Builder builder = ConfigurationTransformation.builder();
        // ADD FUTURE TRANSFORMS HERE (these transforms run after the defaults have been merged into the node)
        DEFAULT_AWARE_TRANSFORMATIONS.forEach(transform -> transform.apply(builder, contextMap, defaultsNode));
        builder.build().apply(worldNode);
    }

    @Override
    public WorldConfiguration createWorldConfig(final ContextMap contextMap) {
        final String levelName = contextMap.require(WORLD_NAME);
        try {
            return super.createWorldConfig(contextMap);
        } catch (IOException exception) {
            throw new RuntimeException("Could not create world config for " + levelName, exception);
        }
    }

    @Override
    protected boolean isConfigType(final Type type) {
        return ConfigurationPart.class.isAssignableFrom(erase(type));
    }

    public void reloadConfigs(MinecraftServer server) {
        try {
            this.initializeGlobalConfiguration(reloader(this.globalConfigClass, GlobalConfiguration.get()));
            this.initializeWorldDefaultsConfiguration(server.registryAccess());
            for (ServerLevel level : server.getAllLevels()) {
                this.createWorldConfig(createWorldContextMap(level), reloader(this.worldConfigClass, level.paperConfig()));
            }
        } catch (Exception ex) {
            throw new RuntimeException("Could not reload paper configuration files", ex);
        }
    }

    private static ContextMap createWorldContextMap(ServerLevel level) {
        return createWorldContextMap(level.convertable.levelDirectory.path(), level.serverLevelData.getLevelName(), level.dimension().location(), level.registryAccess(), level.getGameRules());
    }

    public static ContextMap createWorldContextMap(final Path dir, final String levelName, final ResourceLocation worldKey, final RegistryAccess registryAccess, final GameRules gameRules) {
        return ContextMap.builder()
                .put(WORLD_DIRECTORY, dir)
                .put(WORLD_NAME, levelName)
                .put(WORLD_KEY, worldKey)
                .put(REGISTRY_ACCESS, registryAccess)
                .put(GAME_RULES, gameRules)
                .build();
    }

    public static PaperConfigurations setup(final Path legacyConfig, final Path configDir, final Path worldFolder) throws Exception {
        final Path legacy = Files.isSymbolicLink(legacyConfig) ? Files.readSymbolicLink(legacyConfig) : legacyConfig;
        try {
            createDirectoriesSymlinkAware(configDir);
            return new PaperConfigurations(configDir);
        } catch (final IOException ex) {
            throw new RuntimeException("Could not setup PaperConfigurations", ex);
        }
    }

    @VisibleForTesting
    static ConfigurationNode createForTesting() {
        ObjectMapper.Factory factory = defaultGlobalFactoryBuilder(ObjectMapper.factoryBuilder()).build();
        ConfigurationOptions options = defaultGlobalOptions(defaultOptions(ConfigurationOptions.defaults()))
                .serializers(builder -> builder.register(type -> ConfigurationPart.class.isAssignableFrom(erase(type)), factory.asTypeSerializer()));
        return BasicConfigurationNode.root(options);
    }

    // Symlinks are not correctly checked in createDirectories
    static void createDirectoriesSymlinkAware(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.createDirectories(path);
        }
    }
}
