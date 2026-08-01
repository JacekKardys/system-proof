package io.github.jacekkardys.systemproof.junit;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs and injects the concrete environment declared by its facade type.
 */
@Retention(RUNTIME)
@Target(TYPE)
@Inherited
@ExtendWith({
    SystemProofLifecycleExtension.class,
    SystemProofFailureTrackingExtension.class,
    SystemProofParameterResolver.class
})
public @interface SystemProof {

    Class<? extends Environment> environment();
}
