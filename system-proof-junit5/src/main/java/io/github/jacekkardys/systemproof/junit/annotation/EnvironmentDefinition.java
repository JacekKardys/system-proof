package io.github.jacekkardys.systemproof.junit.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks the single factory method that defines a concrete System Proof environment.
 *
 * <p>The annotated method may have any visibility, but it must be static, declare no
 * parameters, and return exactly its declaring environment type. Each environment type used by
 * {@link SystemProof} must declare exactly one such method.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface EnvironmentDefinition {}
