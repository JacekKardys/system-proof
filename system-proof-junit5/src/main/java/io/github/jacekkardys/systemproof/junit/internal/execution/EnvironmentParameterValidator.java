package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/** Validates injected environment parameters on System Proof test and lifecycle methods. */
public final class EnvironmentParameterValidator {

    private static final List<ValidationRule<ParameterDeclaration>> PARAMETER_RULES = List.of(
        new ValidationRule<>(
            "at most one environment parameter may be declared",
            declaration -> declaration.environmentParameters().size() <= 1
        ),
        new ValidationRule<>(
            "the environment parameter must have the exact configured type",
            declaration -> declaration.environmentParameters().isEmpty()
                || declaration.environmentParameters().getFirst() == declaration.environmentType()
        )
    );

    public void validateConfiguration(
        Executable executable,
        Class<? extends Environment> environmentType
    ) {
        val violation = findViolation(executable, environmentType);
        if (violation != null) {
            throw new ExtensionConfigurationException(violation);
        }
    }

    public void validateResolution(
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

        val declaration = ParameterDeclaration.of(executable, environmentType);
        val violation = ValidationRule.firstViolation(declaration, PARAMETER_RULES).orElse(null);
        if (violation == null) {
            return null;
        }

        return "Method '" + declaration.executable().getDeclaringClass().getName() + "#"
            + declaration.executable().getName()
            + "' may declare at most one environment parameter and it must have the exact type "
            + declaration.environmentType().getName() + "; violated rule="
            + violation.description() + "; actual environment parameters="
            + declaration.environmentParameters().stream().map(Class::getName).toList();
    }

    private record ParameterDeclaration(
        Executable executable,
        Class<? extends Environment> environmentType,
        List<Class<?>> environmentParameters
    ) {
        private static ParameterDeclaration of(
            Executable executable,
            Class<? extends Environment> environmentType
        ) {
            val environmentParameters = Arrays.stream(executable.getParameterTypes())
                .filter(Environment.class::isAssignableFrom)
                .toList();
            return new ParameterDeclaration(executable, environmentType, environmentParameters);
        }
    }
}
