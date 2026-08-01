package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/** Validates environment parameters on System Proof test and lifecycle methods. */
final class SystemProofParameterValidator {

    void validateConfiguration(
        Executable executable,
        Class<? extends Environment> environmentType
    ) {
        val violation = findViolation(executable, environmentType);
        if (violation != null) {
            throw new ExtensionConfigurationException(violation);
        }
    }

    void validateResolution(
        Executable executable,
        Class<? extends Environment> environmentType
    ) {
        val violation = findViolation(executable, environmentType);
        if (violation != null) {
            throw new ParameterResolutionException(violation);
        }
    }

    private static String findViolation(
        Executable executable,
        Class<? extends Environment> environmentType
    ) {
        Objects.requireNonNull(executable, "executable must not be null");
        Objects.requireNonNull(environmentType, "environmentType must not be null");

        val environmentParameters = Arrays.stream(executable.getParameterTypes())
            .filter(Environment.class::isAssignableFrom)
            .toList();
        if (environmentParameters.isEmpty()
            || environmentParameters.equals(List.of(environmentType))) {
            return null;
        }

        return "Method '" + executable.getDeclaringClass().getName() + "#" + executable.getName()
            + "' may declare at most one environment parameter and it must have the exact type "
            + environmentType.getName() + "; actual environment parameters="
            + environmentParameters.stream().map(Class::getName).toList();
    }
}
