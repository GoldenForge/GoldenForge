package io.papermc.paper.configuration;

import org.spongepowered.configurate.loader.*;
import org.spongepowered.configurate.util.*;
import org.spongepowered.configurate.yaml.*;

import java.nio.file.*;

public final class ConfigurationLoaders {
    private ConfigurationLoaders() {
    }

    public static YamlConfigurationLoader.Builder naturallySorted() {
        return YamlConfigurationLoader.builder()
            .indent(2)
            .nodeStyle(NodeStyle.BLOCK)
            .headerMode(HeaderMode.PRESET)
            .defaultOptions(options -> options.mapFactory(MapFactories.sortedNatural()));
    }

    public static YamlConfigurationLoader naturallySortedWithoutHeader(final Path path) {
        return naturallySorted()
            .headerMode(HeaderMode.NONE)
            .path(path)
            .build();
    }
}
