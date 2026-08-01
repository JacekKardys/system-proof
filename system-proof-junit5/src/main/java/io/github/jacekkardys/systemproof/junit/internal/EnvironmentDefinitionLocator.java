package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** The only reflection adapter: validates and invokes one environment facade definition. */
final class EnvironmentDefinitionLocator {
    private static final String EXPECTED =
        "@EnvironmentDefinition static <E extends Environment> define()";

    LocatedDefinition locate(Class<? extends Environment> environmentType) {
        if (environmentType == Environment.class
            || Modifier.isAbstract(environmentType.getModifiers())) {
            throw invalid(
                environmentType,
                null,
                "environment type must be concrete",
                environmentType.getTypeName()
            );
        }

        val methods = Arrays.stream(environmentType.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EnvironmentDefinition.class))
            .toList();
        if (methods.size() != 1) {
            val actual = methods.isEmpty()
                ? "none"
                : methods.stream().map(EnvironmentDefinitionLocator::signature)
                    .sorted().collect(Collectors.joining(", "));
            throw invalid(
                environmentType,
                null,
                "expected exactly one definition but found " + methods.size(),
                actual
            );
        }

        val method = methods.getFirst();
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
        validateReturnType(environmentType, method);
        if (!method.trySetAccessible()) {
            throw invalid(
                environmentType,
                method,
                "definition method is not accessible",
                signature(method)
            );
        }
        return new LocatedDefinition(method, environmentType);
    }

    private static void validateReturnType(
        Class<? extends Environment> environmentType,
        Method method
    ) {
        if (method.getReturnType() != environmentType) {
            throw invalid(
                environmentType,
                method,
                "return type must match the declared environment type " + environmentType.getName(),
                signature(method)
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

    record LocatedDefinition(Method method, Class<? extends Environment> environmentType) {
        Environment invoke() {
            try {
                val result = method.invoke(null);
                if (result == null) {
                    throw invalid(
                        method.getDeclaringClass(),
                        method,
                        "definition returned null",
                        signature(method)
                    );
                }
                if (!environmentType.isInstance(result)) {
                    throw invalid(
                        method.getDeclaringClass(),
                        method,
                        "definition returned " + result.getClass().getName(),
                        signature(method)
                    );
                }
                return environmentType.cast(result);
            } catch (IllegalAccessException exception) {
                throw new ExtensionConfigurationException(
                    "Cannot invoke @EnvironmentDefinition method '" + qualifiedName() + "'",
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
                    "@EnvironmentDefinition method '" + qualifiedName() + "' failed",
                    cause
                );
            }
        }

        private String qualifiedName() {
            return method.getDeclaringClass().getName() + "#" + method.getName();
        }
    }
}
