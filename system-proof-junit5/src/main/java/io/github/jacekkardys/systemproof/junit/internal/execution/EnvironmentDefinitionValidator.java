package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** Validates the factory contract declared by {@link EnvironmentDefinition}. */
final class EnvironmentDefinitionValidator {

    private static final String EXPECTED = "@EnvironmentDefinition static <E extends Environment> define()";
    private static final ValidationRule<Class<? extends Environment>> CONCRETE_ENVIRONMENT =
        new ValidationRule<>(
            "environment type must be concrete",
            environmentType -> environmentType != Environment.class
                && !Modifier.isAbstract(environmentType.getModifiers())
        );
    private static final ValidationRule<List<Method>> SINGLE_DEFINITION =
        new ValidationRule<>(
            "expected exactly one definition",
            methods -> methods.size() == 1
        );
    private static final List<ValidationRule<DefinitionMethod>> DEFINITION_RULES = List.of(
        new ValidationRule<>(
            "definition must be a static method",
            definition -> Modifier.isStatic(definition.method().getModifiers())
        ),
        new ValidationRule<>(
            "definition must not declare parameters",
            definition -> definition.method().getParameterCount() == 0
        ),
        new ValidationRule<>(
            "return type must match the declared environment type",
            definition -> definition.method().getReturnType() == definition.environmentType()
        )
    );
    private static final ValidationRule<Object> NON_NULL_DEFINITION_RESULT =
        new ValidationRule<>("definition returned null", Objects::nonNull);

    void validate(
        Class<? extends Environment> environmentType,
        List<Method> definitions
    ) {
        validateEnvironmentType(environmentType);
        validateDefinitionCount(environmentType, definitions);
        validateDefinition(environmentType, definitions.getFirst());
    }

    void validateResult(
        Class<? extends Environment> environmentType,
        Method definition,
        Object result
    ) {
        if (NON_NULL_DEFINITION_RESULT.isViolatedBy(result)) {
            throw invalid(
                environmentType,
                definition,
                NON_NULL_DEFINITION_RESULT.description(),
                signature(definition)
            );
        }
    }

    private static void validateEnvironmentType(Class<? extends Environment> environmentType) {
        if (CONCRETE_ENVIRONMENT.isViolatedBy(environmentType)) {
            throw invalid(
                environmentType,
                null,
                CONCRETE_ENVIRONMENT.description(),
                environmentType.getTypeName()
            );
        }
    }

    private static void validateDefinitionCount(
        Class<? extends Environment> environmentType,
        List<Method> definitions
    ) {
        if (SINGLE_DEFINITION.isViolatedBy(definitions)) {
            val actual = definitions.isEmpty()
                ? "none"
                : definitions.stream().map(EnvironmentDefinitionValidator::signature)
                    .sorted().collect(Collectors.joining(", "));
            throw invalid(
                environmentType,
                null,
                SINGLE_DEFINITION.description() + " but found " + definitions.size(),
                actual
            );
        }
    }

    private static void validateDefinition(
        Class<? extends Environment> environmentType,
        Method method
    ) {
        val definition = new DefinitionMethod(environmentType, method);
        val violation = ValidationRule.firstViolation(definition, DEFINITION_RULES).orElse(null);
        if (violation != null) {
            throw invalid(
                environmentType,
                method,
                violation.description(),
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

    private record DefinitionMethod(
        Class<? extends Environment> environmentType,
        Method method
    ) {}
}
