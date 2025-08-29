package io.papermc.paper.configuration;

import org.checkerframework.checker.nullness.qual.*;
import org.spongepowered.configurate.objectmapping.meta.*;

import java.lang.annotation.*;
import java.lang.reflect.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NestedSetting {

    String[] value();

    class Factory implements NodeResolver.Factory {
        @Override
        public @Nullable NodeResolver make(String name, AnnotatedElement element) {
            if (element.isAnnotationPresent(NestedSetting.class)) {
                Object[] path = element.getAnnotation(NestedSetting.class).value();
                if (path.length > 0) {
                    return node -> node.node(path);
                }
            }
            return null;
        }
    }
}
