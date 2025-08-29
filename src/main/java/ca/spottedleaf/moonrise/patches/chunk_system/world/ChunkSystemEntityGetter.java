package ca.spottedleaf.moonrise.patches.chunk_system.world;

import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;

import java.util.*;
import java.util.function.*;

public interface ChunkSystemEntityGetter {

    public List<Entity> moonrise$getHardCollidingEntities(final Entity entity, final AABB box, final Predicate<? super Entity> predicate);

}
