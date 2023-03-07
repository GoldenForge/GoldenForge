package org.bukkit;

public class Bukkit {
    public static boolean isPrimaryThread() {
        return io.papermc.paper.util.TickThread.isTickThread(); // Paper - rewrite chunk system
    }
}
