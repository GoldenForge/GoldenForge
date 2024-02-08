package org.spigotmc;

import net.minecraft.server.MinecraftServer;

public class AsyncCatcher
{

    public static boolean enabled = true;

    public static void catchOp(String reason)
    {
        if (!(io.papermc.paper.util.TickThread.isTickThread())) // Paper
        {
            MinecraftServer.LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable()); // Paper
            throw new IllegalStateException( "Asynchronous " + reason + "!" );
        }
    }
}
