package org.goldenforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goldenforge.commands.GoldenForgeCommand;
import org.goldenforge.tpsmonitor.TpsMonitorManager;

import java.io.File;

@Mod("goldenforge")
@OnlyIn(Dist.DEDICATED_SERVER)
public class GoldenForge {
    public static Logger LOGGER = LogManager.getLogger("GoldenForge");

    public static String getBranding() {
        return "Goldenforge 1.21.1 " + GoldenForgeEntryPoint.getVersion();
    }

    public GoldenForge() {
        LOGGER.info("Loading GoldenForge");
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(this::onCommandsRegister);
        NeoForge.EVENT_BUS.register(new TpsMonitorManager());

//        File configDir = new File(".", "goldenforge"); configDir.mkdirs();
//        ModConfig config = new ModConfig(ModConfig.Type.SERVER, GoldenForgeConfig.serverSpec, ModLoadingContext.get().getActiveContainer(), "goldenforge.toml", true);
//        openConfig(config, configDir.toPath());
//        final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
//        modEventBus.register(GoldenForgeConfig.class);
//        Config.load(true);
//        Config.save(true);

        if (!ModList.get().isLoaded("goldenforgefixes")) {
            LOGGER.warn("GoldenForgeFixes in not installed, some mod might not work well wihout it. (https://modrinth.com/mod/goldenforge-fixes) ");
        }
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        GoldenForgeCommand.register(event.getDispatcher());
    }

//    private static void openConfig(final ModConfig config, final Path configBasePath) {
//        LOGGER.info("Loading config file type {} at {} for {}", config.getType(), config.getFileName(), config.getModId());
//        final CommentedFileConfig configData = config.getHandler().reader(configBasePath).apply(config);
//        config.setConfigData(configData);
//        config.fireEvent(IConfigEvent.loading(config));
//        config.save();
//    }
}
