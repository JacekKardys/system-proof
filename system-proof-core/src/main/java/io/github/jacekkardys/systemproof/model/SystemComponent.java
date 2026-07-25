package io.github.jacekkardys.systemproof.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

/** Declares the stable type and runtime driver of one concrete component kind. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SystemComponent {
    String type();

    Class<? extends ComponentDriver<?, ?>> driver();
}
