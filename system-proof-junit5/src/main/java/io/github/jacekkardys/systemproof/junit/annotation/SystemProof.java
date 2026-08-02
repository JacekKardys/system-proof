package io.github.jacekkardys.systemproof.junit.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.jacekkardys.systemproof.junit.internal.SystemProofInvocationContextProvider;
import io.github.jacekkardys.systemproof.junit.internal.SystemProofLifecycleExtension;
import io.github.jacekkardys.systemproof.junit.internal.SystemProofParameterResolver;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares a test that runs with a fresh System Proof environment and injects its concrete facade.
 *
 * <p>The selected environment type must be concrete and declare exactly one static,
 * zero-argument {@link EnvironmentDefinition} method returning that exact type. The annotated test
 * and its per-test lifecycle methods may declare one parameter of the selected environment type.
 */
@Retention(RUNTIME)
@Target(METHOD)
@TestTemplate
@ExtendWith({
    SystemProofInvocationContextProvider.class,
    SystemProofLifecycleExtension.class,
    SystemProofParameterResolver.class
})
public @interface SystemProof {
    /**
     * Returns the concrete environment facade created, started, injected, and closed for this test
     * invocation.
     *
     * @return concrete environment facade type
     */
    Class<? extends Environment> value();

    /**
     * Returns the optional scenario title used as the test display name and published to the JUnit
     * report as {@code system-proof.title}.
     *
     * <p>The Java method name is used as the display name when the title is omitted.
     *
     * @return test title, or an empty string when omitted
     */
    String title() default "";

    /**
     * Returns the optional scenario description published to the JUnit report as
     * {@code system-proof.description}.
     *
     * @return test description, or an empty string when omitted
     */
    String description() default "";
}
