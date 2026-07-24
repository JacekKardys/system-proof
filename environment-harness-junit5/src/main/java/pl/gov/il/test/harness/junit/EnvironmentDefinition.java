package pl.gov.il.test.harness.junit;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Marks the single static no-argument factory on a concrete environment facade. */
@Retention(RUNTIME)
@Target(METHOD)
public @interface EnvironmentDefinition {}
