package io.papermc.paper.configuration.transformation.global.versioned;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.transformation.TransformAction;

import java.util.Properties;

import static org.spongepowered.configurate.NodePath.path;

public class V29_LogIPs implements TransformAction {

    private static final int VERSION = 29;
    private static final NodePath PATH = path("logging", "log-player-ip-addresses");
    private static final V29_LogIPs INSTANCE = new V29_LogIPs();

    private V29_LogIPs() {
    }

    public static void apply(ConfigurationTransformation.VersionedBuilder builder) {
        builder.addVersion(VERSION, ConfigurationTransformation.builder().addAction(PATH, INSTANCE).build());
    }

    @Override
    public Object @Nullable [] visitPath(final NodePath path, final ConfigurationNode value) throws ConfigurateException {
        DedicatedServer server = ((DedicatedServer) MinecraftServer.getServer());

        boolean val = value.getBoolean(server.settings.getProperties().logIPs);
        server.settings.update((config) -> {
            Properties newProps = new Properties(config.properties);
            newProps.setProperty("log-ips", String.valueOf(val));
            return config.reload(server.registryAccess(), newProps, server.options);
        });

        value.raw(null);

        return null;
    }

}
