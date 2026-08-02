package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** Creates an environment facade through its validated {@link EnvironmentDefinition} method. */
public final class EnvironmentFactory {
    private static final String EXPECTED =
        "@EnvironmentDefinition static <E extends Environment> define()";

    public <E extends Environment> E create(Class<E> environmentType) {
        validateEnvironmentType(environmentType);
        val definition = findDefinition(environmentType);
        validateDefinition(environmentType, definition);
        makeAccessible(environmentType, definition);
        return invoke(environmentType, definition);
    }

    private static void validateEnvironmentType(Class<? extends Environment> environmentType) {
        if (environmentType == Environment.class
            || Modifier.isAbstract(environmentType.getModifiers())) {
            throw invalid(
                environmentType,
                null,
                "environment type must be concrete",
                environmentType.getTypeName()
            );
        }
    }

    private static Method findDefinition(Class<? extends Environment> environmentType) {
        val methods = Arrays.stream(environmentType.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EnvironmentDefinition.class))
            .toList();
        if (methods.size() != 1) {
            val actual = methods.isEmpty()
                ? "none"
                : methods.stream().map(EnvironmentFactory::signature)
                    .sorted().collect(Collectors.joining(", "));
            throw invalid(
                environmentType,
                null,
                "expected exactly one definition but found " + methods.size(),
                actual
            );
        }
        return methods.getFirst();
    }

    private static void validateDefinition(
        Class<? extends Environment> environmentType,
        Method method
    ) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw invalid(
                environmentType,
                method,
                "definition must be a static method",
                signature(method)
            );
        }
        if (method.getParameterCount() != 0) {
            throw invalid(
                environmentType,
                method,
                "definition must not declare parameters",
                signature(method)
            );
        }
        if (method.getReturnType() != environmentType) {
            throw invalid(
                environmentType,
                method,
                "return type must match the declared environment type " + environmentType.getName(),
                signature(method)
            );
        }
    }

    private static void makeAccessible(
        Class<? extends Environment> environmentType,
        Method method
    ) {
        if (!method.trySetAccessible()) {
            throw invalid(
                environmentType,
                method,
                "definition method is not accessible",
                signature(method)
            );
        }
    }

    private static <E extends Environment> E invoke(
        Class<E> environmentType,
        Method method
    ) {
        try {
            val result = method.invoke(null);
            if (result == null) {
                throw invalid(
                    environmentType,
                    method,
                    "definition returned null",
                    signature(method)
                );
            }
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

    private static ExtensionConfigurationException invalid(
        Class<?> environmentType,
        Method method,
        String reason,
        String actual
    ) {
        val location = method == null
            ? environmentType.getName()
            : environmentType.getName() + "#" + method.getName();
        return new ExtensionConfigurationException(
            "Invalid environment definition at '" + location + "': " + reason
                + "; expected=" + EXPECTED + "; actual=" + actual
        );
    }

    private static String signature(Method method) {
        return (Modifier.isStatic(method.getModifiers()) ? "static " : "")
            + method.getReturnType().getTypeName() + " " + method.getName() + "("
            + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String qualifiedName(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
