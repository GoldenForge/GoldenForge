package io.papermc.paper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public class MCUtil {

    public static long getCoordinateKey(final BlockPos blockPos) {
        return ((long)(blockPos.getZ() >> 4) << 32) | ((blockPos.getX() >> 4) & 0xFFFFFFFFL);
    }

    public static long getCoordinateKey(final Entity entity) {
        return ((long)(entity.blockPosition.getZ() >> 4) << 32) | ((entity.blockPosition.getX() >> 4) & 0xFFFFFFFFL); // Pufferfish - eliminate double->long cast in hotpath
    }

    public static int fastFloor(double x) {
        int truncated = (int)x;
        return x < (double)truncated ? truncated - 1 : truncated;
    }

    public static long getCoordinateKey(final ChunkPos pair) {
        return ((long)pair.z << 32) | (pair.x & 0xFFFFFFFFL);
    }

    public static long getCoordinateKey(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }


    public static int getCoordinateX(final long key) {
        return (int)key;
    }

    public static int getCoordinateZ(final long key) {
        return (int)(key >>> 32);
    }

    public static <T> T ensureMain(String reason, Supplier<T> run) {
        if (Thread.currentThread() != MinecraftServer.getServer().serverThread) {
            if (reason != null) {
                new IllegalStateException("Asynchronous " + reason + "! Blocking thread until it returns ").printStackTrace();
            }
            Waitable<T> wait = new Waitable<T>() {
                @Override
                protected T evaluate() {
                    return run.get();
                }
            };
            getProcessQueue().add(wait);
            try {
                return wait.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
            return null;
        }
        return run.get();
    }

    private static Queue<Runnable> getProcessQueue() {
        return MinecraftServer.getServer().processQueue;
    }
}
