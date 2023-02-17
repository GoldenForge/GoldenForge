package com.destroystokyo.paper.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public class MCUtil {

    public static float normalizeYaw(float f) {
        float f1 = f % 360.0F;

        if (f1 >= 180.0F) {
            f1 -= 360.0F;
        }

        if (f1 < -180.0F) {
            f1 += 360.0F;
        }

        return f1;
    }

    public static List<ChunkPos> getSpiralOutChunks(BlockPos blockposition, int radius) {
        List<ChunkPos> list = com.google.common.collect.Lists.newArrayList();

        list.add(new ChunkPos(blockposition.getX() >> 4, blockposition.getZ() >> 4));
        for (int r = 1; r <= radius; r++) {
            int x = -r;
            int z = r;

            // Iterates the edge of half of the box; then negates for other half.
            while (x <= r && z > -r) {
                list.add(new ChunkPos((blockposition.getX() + (x << 4)) >> 4, (blockposition.getZ() + (z << 4)) >> 4));
                list.add(new ChunkPos((blockposition.getX() - (x << 4)) >> 4, (blockposition.getZ() - (z << 4)) >> 4));

                if (x < r) {
                    x++;
                } else {
                    z--;
                }
            }
        }
        return list;
    }

    public static int getTicketLevelFor(net.minecraft.world.level.chunk.ChunkStatus status) {
        return net.minecraft.server.level.ChunkMap.MAX_VIEW_DISTANCE + net.minecraft.world.level.chunk.ChunkStatus.getDistance(status);
    }
}
