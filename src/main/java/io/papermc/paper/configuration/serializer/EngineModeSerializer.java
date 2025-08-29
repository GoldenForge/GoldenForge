package io.papermc.paper.configuration.serializer;

import io.papermc.paper.configuration.type.*;
import org.spongepowered.configurate.serialize.*;

import java.lang.reflect.*;
import java.util.function.*;

public final class EngineModeSerializer extends ScalarSerializer<EngineMode> {

    public EngineModeSerializer() {
        super(EngineMode.class);
    }

    @Override
    public EngineMode deserialize(Type type, Object obj) throws SerializationException {
        if (obj instanceof Integer id) {
            try {
                return EngineMode.valueOf(id);
            } catch (IllegalArgumentException e) {
                throw new SerializationException(id + " is not a valid id for type " + type + " for this node");
            }
        }

        throw new SerializationException(obj + " is not of a valid type " + type + " for this node");
    }

    @Override
    protected Object serialize(EngineMode item, Predicate<Class<?>> typeSupported) {
        return item.getId();
    }
}
