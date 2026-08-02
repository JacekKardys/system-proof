package io.github.jacekkardys.systemproof.junit.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.jacekkardys.systemproof.junit.internal.extension.EnvironmentLifecycleExtension;
import io.github.jacekkardys.systemproof.junit.internal.extension.EnvironmentParameterResolver;
import io.github.jacekkardys.systemproof.junit.internal.extension.SystemProofInvocationProvider;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares a test that runs with a fresh System Proof environment and injects it through its
 * declared facade type.
 *
 * <p>The selected environment type must be concrete and declare exactly one static,
 * zero-argument {@link EnvironmentDefinition} method returning that exact type. The definition may
 * create a subtype, but the selected type remains the injection contract. The annotated test and
 * its per-test lifecycle methods may declare one parameter of the selected environment type.
 */
@Retention(RUNTIME)
@Target(METHOD)
@TestTemplate
@ExtendWith({
    SystemProofInvocationProvider.class,
    EnvironmentLifecycleExtension.class,
    EnvironmentParameterResolver.class
})
public @interface SystemProof {
    /**
     * Returns the declared environment facade type used as the injection contract for this test
     * invocation.
     *
     * @return declared environment facade type
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
