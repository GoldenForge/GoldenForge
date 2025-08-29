package io.papermc.paper.configuration.serializer;

import com.mojang.logging.*;
import io.leangen.geantyref.*;
import org.checkerframework.checker.nullness.qual.*;
import org.slf4j.*;
import org.spongepowered.configurate.serialize.*;
import org.spongepowered.configurate.util.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static io.leangen.geantyref.GenericTypeReflector.*;

/**
 * Enum serializer that lists options if fails and accepts `-` as `_`.
 */
public class EnumValueSerializer extends ScalarSerializer<Enum<?>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    public EnumValueSerializer() {
        super(new TypeToken<Enum<?>>() {});
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public @Nullable Enum<?> deserialize(final Type type, final Object obj) throws SerializationException {
        final String enumConstant = obj.toString();
        final Class<? extends Enum> typeClass = erase(type).asSubclass(Enum.class);
        @Nullable Enum<?> ret = EnumLookup.lookupEnum(typeClass, enumConstant);
        if (ret == null) {
            ret = EnumLookup.lookupEnum(typeClass, enumConstant.replace("-", "_"));
        }
        if (ret == null) {
            boolean longer = typeClass.getEnumConstants().length > 10;
            List<String> options = Arrays.stream(typeClass.getEnumConstants()).limit(10L).map(Enum::name).toList();
            LOGGER.error("Invalid enum constant provided, expected one of [" + String.join(", " ,options) + (longer ? ", ..." : "") + "], but got " + enumConstant);
        }
        return ret;
    }

    @Override
    public Object serialize(final Enum<?> item, final Predicate<Class<?>> typeSupported) {
        return item.name();
    }
}
