package org.goldenforge;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.command.ConfigCommand;
import net.minecraftforge.server.command.ForgeCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goldenforge.commands.GoldenForgeCommand;
import org.goldenforge.config.GoldenForgeConfig;
import me.wesley1808.servercore.Config;
import org.goldenforgelauncher.GoldenForgeEntryPoint;

import java.io.File;
import java.nio.file.Path;

@Mod("goldenforge")
public class GoldenForge {
    public static Logger LOGGER = LogManager.getLogger("GoldenForge");

    public static String getBranding() {
        return "Goldenforge 1.19.2 " + GoldenForgeEntryPoint.getVersion();
    }

    public GoldenForge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        new GoldenForgeCommand(event.getDispatcher());
    }

    public static void init() {
        File configDir = new File(".", "goldenforge"); configDir.mkdirs();
        ModConfig config = new ModConfig(ModConfig.Type.SERVER, GoldenForgeConfig.serverSpec, ModLoadingContext.get().getActiveContainer(), "goldenforge.toml", true);
        openConfig(config, configDir.toPath());
        final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.register(GoldenForgeConfig.class);
        Config.load(true);
        Config.save(true);
    }

    private static void openConfig(final ModConfig config, final Path configBasePath) {
        LOGGER.info("Loading config file type {} at {} for {}", config.getType(), config.getFileName(), config.getModId());
        final CommentedFileConfig configData = config.getHandler().reader(configBasePath).apply(config);
        config.setConfigData(configData);
        config.fireEvent(IConfigEvent.loading(config));
        config.save();
    }
}
