package io.github.jacekkardys.systemproof.junit;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import io.github.jacekkardys.systemproof.model.Environment;

/** Runs and injects the concrete environment declared by its facade type. */
@Retention(RUNTIME)
@Target(TYPE)
@Inherited
@ExtendWith(EnvironmentTestExtension.class)
public @interface EnvironmentTest {
    Class<? extends Environment> environment();
}
