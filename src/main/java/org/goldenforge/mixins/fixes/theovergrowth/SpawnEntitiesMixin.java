package org.goldenforge.mixins.fixes.theovergrowth;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import com.ldtteam.overgrowth.handlers.SpawnEntities;
import com.ldtteam.overgrowth.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(SpawnEntities.class)
public class SpawnEntitiesMixin {

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void transformBlock(final BlockPos relativePos, final LevelChunk chunk, final int chunkSection, final BlockState input)
    {
        if (!((ServerLevel) chunk.getLevel()).isNaturalSpawningAllowed(chunk.getPos()))
        {
            return;
        }

        final EntityLookup entityLookup = ((ServerLevel) chunk.getLevel()).moonrise$getEntityLookup();
        for (int x = chunk.getPos().x - 3; x <= chunk.getPos().x + 3; x++)
        {
            for (int z = chunk.getPos().z - 3; z <= chunk.getPos().z + 3; z++)
            {
                for (int i = 0; i <= 12; i++)
                {
                    final ChunkEntitySlices slices =  entityLookup.getChunk(x, z);
                    if (slices == null)
                    {
                        continue;
                    }

                    if (!slices.isEmpty())
                    {
                        return;
                    }
                }
            }
        }

        final EntityType type;
        final int rand = chunk.getLevel().random.nextInt(4);
        switch (rand)
        {
            case 0:
                type = EntityType.COW;
                break;
            case 1:
                type = EntityType.SHEEP;
                break;
            case 2:
                type = EntityType.CHICKEN;
                break;
            default:
                type = EntityType.PIG;
                break;
        }

        final BlockPos worldPos = Utils.getWorldPos(chunk, chunkSection, relativePos.above());

        for (int i = 0; i < 2; i++)
        {
            final Entity entity = type.create(chunk.getLevel());
            entity.setPos(worldPos.getX() + 0.5, worldPos.getY(), worldPos.getZ() + 0.5);
            chunk.getLevel().addFreshEntity(entity);
        }
    }

}
