package org.bukkit;

import net.minecraft.server.MinecraftServer;

public class Bukkit {

    public static boolean isPrimaryThread() {
        return MinecraftServer.getServer().isSameThread();
    }
}
