package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/**
 * Resolves a declared environment type to a fresh instance by locating, validating, and invoking
 * its {@link EnvironmentDefinition} method.
 */
public final class EnvironmentDefinitionResolver {
    private final EnvironmentDefinitionLocator locator = new EnvironmentDefinitionLocator();
    private final EnvironmentDefinitionValidator validator = new EnvironmentDefinitionValidator();

    public <E extends Environment> E resolve(Class<E> environmentType) {
        val definitions = locator.findAll(environmentType);
        validator.validate(environmentType, definitions);
        val definition = definitions.getFirst();
        makeAccessible(definition);
        return invoke(environmentType, definition);
    }

    static void makeAccessible(Method method) {
        try {
            if (method.trySetAccessible()) {
                return;
            }
        } catch (SecurityException exception) {
            throw inaccessible(method, exception);
        }
        throw inaccessible(method, null);
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

    private static ExtensionConfigurationException inaccessible(
        Method method,
        SecurityException cause
    ) {
        val declaringClass = method.getDeclaringClass();
        val declaringModule = moduleName(declaringClass.getModule());
        val frameworkModule = moduleName(EnvironmentDefinitionResolver.class.getModule());
        val message = "System Proof could not obtain reflective access to "
            + "@EnvironmentDefinition method '" + method.toGenericString() + "' declared by '"
            + declaringClass.getName() + "'. Package '" + declaringClass.getPackageName()
            + "' in module '" + declaringModule + "' may need to be opened to framework module '"
            + frameworkModule + "'";
        return cause == null
            ? new ExtensionConfigurationException(message)
            : new ExtensionConfigurationException(message, cause);
    }

    private static String moduleName(Module module) {
        return module.isNamed() ? module.getName() : "unnamed";
    }
}
