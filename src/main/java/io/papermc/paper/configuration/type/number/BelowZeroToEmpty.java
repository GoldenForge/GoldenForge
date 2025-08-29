package io.papermc.paper.configuration.type.number;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BelowZeroToEmpty {
}
