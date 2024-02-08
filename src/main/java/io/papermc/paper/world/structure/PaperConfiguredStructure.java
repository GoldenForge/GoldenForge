package io.papermc.paper.world.structure;

import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.bukkit.NamespacedKey;
import org.bukkit.StructureType;
import org.bukkit.craftbukkit.CraftRegistry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

@DefaultQualifier(NonNull.class)
@Deprecated(forRemoval = true)
public final class PaperConfiguredStructure {

    private PaperConfiguredStructure() {
    }

    @Deprecated(forRemoval = true)
    public static final class LegacyRegistry extends CraftRegistry<ConfiguredStructure, Structure> {

        public LegacyRegistry(final Registry<Structure> minecraftRegistry) {
            super(ConfiguredStructure.class, minecraftRegistry, LegacyRegistry::minecraftToBukkit);
        }

        private static @Nullable ConfiguredStructure minecraftToBukkit(NamespacedKey key, Structure nms) {
            final ResourceLocation structureTypeLoc = Objects.requireNonNull(BuiltInRegistries.STRUCTURE_TYPE.getKey(nms.type()), "unexpected structure type " + nms.type());
            final @Nullable StructureType structureType = StructureType.getStructureTypes().get(structureTypeLoc.getPath());
            return structureType == null ? null : new ConfiguredStructure(key, structureType);
        }
    }
}
