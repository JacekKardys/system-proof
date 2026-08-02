package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** Creates an environment facade through its validated {@link EnvironmentDefinition} method. */
public final class EnvironmentFactory {
    private final EnvironmentDefinitionLocator locator = new EnvironmentDefinitionLocator();
    private final EnvironmentDefinitionValidator validator = new EnvironmentDefinitionValidator();

    public <E extends Environment> E create(Class<E> environmentType) {
        val definitions = locator.findAll(environmentType);
        validator.validate(environmentType, definitions);
        val definition = definitions.getFirst();
        makeAccessible(definition);
        return invoke(environmentType, definition);
    }

    private static void makeAccessible(Method method) {
        if (!method.trySetAccessible()) {
            throw new ExtensionConfigurationException(
                "Cannot access @EnvironmentDefinition method '%s'".formatted(qualifiedName(method))
            );
        }
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
                "Cannot invoke @EnvironmentDefinition method '%s'".formatted(qualifiedName(method)),
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
