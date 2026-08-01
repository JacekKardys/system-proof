package io.github.jacekkardys.systemproof.junit.annotation;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.jacekkardys.systemproof.junit.internal.SystemProofLifecycleExtension;
import io.github.jacekkardys.systemproof.junit.internal.SystemProofParameterResolver;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a fresh System Proof environment for every test method and injects its concrete facade.
 *
 * <p>The selected environment type must be concrete and declare exactly one static,
 * zero-argument {@link EnvironmentDefinition} method returning that exact type. Each test method
 * must declare exactly one parameter of the selected environment type.
 */
@Retention(RUNTIME)
@Target(TYPE)
@Inherited
@ExtendWith({
    SystemProofLifecycleExtension.class,
    SystemProofParameterResolver.class
})
public @interface SystemProof {
    /**
     * Returns the concrete environment facade created, started, injected, and closed for each
     * test method.
     *
     * @return concrete environment facade type
     */
    Class<? extends Environment> environment();
}
