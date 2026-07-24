package io.github.jacekkardys.systemproof.configuration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares where the value of a typed configuration method is resolved. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigurationSource {
    String UNSET = "\u0000";

    Class<? extends ConfigurationProvider> provider();

    String key() default UNSET;

    String value() default UNSET;

    String defaultValue() default UNSET;
}
