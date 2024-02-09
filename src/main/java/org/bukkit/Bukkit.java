package org.bukkit;

import net.minecraft.server.MinecraftServer;

public class Bukkit {

    public static boolean isPrimaryThread() {
        return io.papermc.paper.util.TickThread.isTickThread(); // Paper - rewrite chunk system
    }

}
