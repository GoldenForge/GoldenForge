package org.goldenforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goldenforge.commands.GoldenForgeCommand;
import org.goldenforge.tpsmonitor.TpsMonitorManager;

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

        if (!ModList.get().isLoaded("goldenforgefixes")) {
            LOGGER.warn("GoldenForgeFixes in not installed, some mod might not work well wihout it. (https://modrinth.com/mod/goldenforge-fixes) ");
        }
    }

    @SubscribeEvent
    public void onCommandsRegister(RegisterCommandsEvent event) {
        GoldenForgeCommand.register(event.getDispatcher());
    }
}
