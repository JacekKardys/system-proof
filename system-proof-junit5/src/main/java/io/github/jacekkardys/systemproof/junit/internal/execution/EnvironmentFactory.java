package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** Creates an environment facade through its validated {@link EnvironmentDefinition} method. */
public final class EnvironmentFactory {
    private final EnvironmentDefinitionValidator validator =
        new EnvironmentDefinitionValidator();

    public <E extends Environment> E create(Class<E> environmentType) {
        val definition = validator.requireValidDefinition(environmentType);
        return invoke(environmentType, definition);
    }

    private <E extends Environment> E invoke(
        Class<E> environmentType,
        Method method
    ) {
        try {
            val result = method.invoke(null);
            validator.validateResult(environmentType, method, result);
            return environmentType.cast(result);
        } catch (IllegalAccessException exception) {
            throw new ExtensionConfigurationException(
                "Cannot invoke @EnvironmentDefinition method '" + qualifiedName(method) + "'",
                exception
            );
        } catch (InvocationTargetException exception) {
            val cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ExtensionConfigurationException(
                "@EnvironmentDefinition method '" + qualifiedName(method) + "' failed",
                cause
            );
        }
    }

    private static String qualifiedName(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
