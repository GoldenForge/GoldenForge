package io.papermc.paper.configuration.type.number;

import com.google.common.base.Preconditions;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.Predicate;
import org.spongepowered.configurate.serialize.ScalarSerializer;

public interface DoubleOr {

    default double or(final double fallback) {
        return this.value().orElse(fallback);
    }

    OptionalDouble value();

    default double doubleValue() {
        return this.value().orElseThrow();
    }

    record Default(OptionalDouble value) implements DoubleOr {
        private static final String DEFAULT_VALUE = "default";
        public static final Default USE_DEFAULT = new Default(OptionalDouble.empty());
        public static final ScalarSerializer<Default> SERIALIZER = new Serializer<>(Default.class, Default::new, DEFAULT_VALUE, USE_DEFAULT);
    }

    final class Serializer<T extends DoubleOr> extends OptionalNumSerializer<T, OptionalDouble> {
        Serializer(final Class<T> classOfT, final Function<OptionalDouble, T> factory, String emptySerializedValue, T emptyValue) {
            super(classOfT, emptySerializedValue, emptyValue, OptionalDouble::empty, OptionalDouble::isEmpty, factory, double.class);
        }

        @Override
        protected Object serialize(final T item, final Predicate<Class<?>> typeSupported) {
            final OptionalDouble value = item.value();
            if (value.isPresent()) {
                return value.getAsDouble();
            } else {
                return this.emptySerializedValue;
            }
        }

        @Override
        protected OptionalDouble full(final String value) {
            return OptionalDouble.of(Double.parseDouble(value));
        }

        @Override
        protected OptionalDouble full(final Number num) {
            return OptionalDouble.of(num.doubleValue());
        }

        @Override
        protected boolean belowZero(final OptionalDouble value) {
            Preconditions.checkArgument(value.isPresent());
            return value.getAsDouble() < 0;
        }
    }
}

