package io.papermc.paper.configuration.serializer.registry;

import com.google.common.base.*;
import io.leangen.geantyref.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import org.spongepowered.configurate.serialize.*;

import java.util.function.Function;

public final class RegistryHolderSerializer<T> extends RegistryEntrySerializer<Holder<T>, T> {

    @SuppressWarnings("unchecked")
    public RegistryHolderSerializer(TypeToken<T> typeToken, final RegistryAccess registryAccess, ResourceKey<? extends Registry<T>> registryKey, boolean omitMinecraftNamespace) {
        super((TypeToken<Holder<T>>) TypeToken.get(TypeFactory.parameterizedClass(Holder.class, typeToken.getType())), registryAccess, registryKey, omitMinecraftNamespace);
    }

    public RegistryHolderSerializer(Class<T> type, final RegistryAccess registryAccess, ResourceKey<? extends Registry<T>> registryKey, boolean omitMinecraftNamespace) {
        this(TypeToken.get(type), registryAccess, registryKey, omitMinecraftNamespace);
        Preconditions.checkArgument(type.getTypeParameters().length == 0, "%s must have 0 type parameters", type);
    }

    @Override
    protected Holder<T> convertFromResourceKey(ResourceKey<T> key) throws SerializationException {
        return this.registry().getHolder(key).orElseThrow(() -> new SerializationException("Missing holder in " + this.registry().key() + " with key " + key));
    }

    @Override
    protected ResourceKey<T> convertToResourceKey(Holder<T> value) {
        return value.unwrap().map(Function.identity(), r -> this.registry().getResourceKey(r).orElseThrow());
    }
}
