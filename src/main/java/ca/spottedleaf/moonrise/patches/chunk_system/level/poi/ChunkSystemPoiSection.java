package ca.spottedleaf.moonrise.patches.chunk_system.level.poi;

import net.minecraft.world.entity.ai.village.poi.*;

import java.util.*;

public interface ChunkSystemPoiSection {

    public boolean moonrise$isEmpty();

    public Optional<PoiSection> moonrise$asOptional();

}
