package ca.spottedleaf.moonrise.patches.chunk_system.async_save;

public record AsyncChunkSaveData(
        net.minecraft.nbt.Tag blockTickList, // non-null if we had to go to the server's tick list
        net.minecraft.nbt.Tag fluidTickList, // non-null if we had to go to the server's tick list
        net.minecraft.nbt.ListTag blockEntities,
        long worldTime
) {}
