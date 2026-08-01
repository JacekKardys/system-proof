package io.github.jacekkardys.systemproof.junit.annotation;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.jacekkardys.systemproof.junit.internal.SystemProofDisplayNameGenerator;
import io.github.jacekkardys.systemproof.junit.internal.SystemProofLifecycleExtension;
import io.github.jacekkardys.systemproof.junit.internal.SystemProofParameterResolver;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a fresh System Proof environment for every test method and injects its concrete facade.
 *
 * <p>The selected environment type must be concrete and declare exactly one static,
 * zero-argument {@link EnvironmentDefinition} method returning that exact type. Test methods and
 * per-test lifecycle methods may declare one parameter of the selected environment type.
 */
@Retention(RUNTIME)
@Target(TYPE)
@Inherited
@DisplayNameGeneration(SystemProofDisplayNameGenerator.class)
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
    Class<? extends Environment> value();

    /**
     * Returns the optional scenario title used as the test class display name and published to
     * the JUnit report as {@code system-proof.title}.
     *
     * @return scenario title, or an empty string when omitted
     */
    String title() default "";

    /**
     * Returns the optional scenario description published to the JUnit report as
     * {@code system-proof.description}.
     *
     * @return scenario description, or an empty string when omitted
     */
    String description() default "";
}
