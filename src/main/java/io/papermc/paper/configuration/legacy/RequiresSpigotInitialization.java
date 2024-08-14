package io.papermc.paper.configuration.legacy;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spigotmc.SpigotWorldConfig;
import org.spongepowered.configurate.objectmapping.meta.NodeResolver;

import java.lang.annotation.*;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RequiresSpigotInitialization {

    Class<? extends NodeResolver> value();

    final class Factory implements NodeResolver.Factory {

        private final SpigotWorldConfig spigotWorldConfig;
        private final Table<Class<? extends NodeResolver>, String, NodeResolver> cache = HashBasedTable.create();

        public Factory(SpigotWorldConfig spigotWorldConfig) {
            this.spigotWorldConfig = spigotWorldConfig;
        }

        @Override
        public @Nullable NodeResolver make(String name, AnnotatedElement element) {
            if (element.isAnnotationPresent(RequiresSpigotInitialization.class)) {
                return this.cache.row(element.getAnnotation(RequiresSpigotInitialization.class).value()).computeIfAbsent(name, key -> {
                    try {
                        final Constructor<? extends NodeResolver> constructor = element.getAnnotation(RequiresSpigotInitialization.class).value().getDeclaredConstructor(String.class, SpigotWorldConfig.class);
                        constructor.trySetAccessible();
                        return constructor.newInstance(key, this.spigotWorldConfig);
                    } catch (final ReflectiveOperationException e) {
                        throw new RuntimeException("Could not create constraint", e);
                    }
                });
            }
            return null;
        }
    }
}
