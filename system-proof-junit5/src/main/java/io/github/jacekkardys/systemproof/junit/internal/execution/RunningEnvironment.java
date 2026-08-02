package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.util.Objects;

/** Immutable association between the declared JUnit contract and its running instance. */
public record RunningEnvironment(
    Class<? extends Environment> declaredType,
    Environment instance
) {
    public RunningEnvironment {
        Objects.requireNonNull(declaredType, "declaredType must not be null");
        Objects.requireNonNull(instance, "instance must not be null");
        if (!declaredType.isInstance(instance)) {
            throw new IllegalArgumentException(
                "Environment instance type '" + instance.getClass().getName()
                    + "' is not assignable to declared type '" + declaredType.getName() + "'"
            );
        }
    }
}
