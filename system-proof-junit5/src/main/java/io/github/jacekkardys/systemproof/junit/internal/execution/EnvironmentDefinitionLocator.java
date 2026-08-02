package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Finds factory methods explicitly declared by an environment facade. */
final class EnvironmentDefinitionLocator {

    List<Method> findAll(Class<? extends Environment> environmentType) {
        Objects.requireNonNull(environmentType, "environmentType must not be null");
        return Arrays.stream(environmentType.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EnvironmentDefinition.class))
            .toList();
    }
}
