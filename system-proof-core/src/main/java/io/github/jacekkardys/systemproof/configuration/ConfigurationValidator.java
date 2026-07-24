package io.github.jacekkardys.systemproof.configuration;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/** Jakarta Validation boundary for immutable component configuration objects. */
@UtilityClass
public class ConfigurationValidator {
    private static final Validator VALIDATOR = Validation
        .byProvider(HibernateValidator.class)
        .configure()
        .messageInterpolator(new ParameterMessageInterpolator())
        .buildValidatorFactory()
        .getValidator();

    public static <T> T validate(T configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        var violations = VALIDATOR.validate(configuration);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return configuration;
    }

    static <T> T validateReturnValues(T configuration, Class<?> contract) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(contract, "contract must not be null");
        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>();
        Arrays.stream(contract.getMethods())
            .filter(method -> Modifier.isAbstract(method.getModifiers()))
            .filter(method -> method.getParameterCount() == 0)
            .distinct()
            .forEach(method -> violations.addAll(
                VALIDATOR.forExecutables().validateReturnValue(
                    configuration,
                    method,
                    invoke(configuration, method)
                )
            ));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return configuration;
    }

    private static Object invoke(Object configuration, Method method) {
        try {
            if (!method.canAccess(configuration) && !method.trySetAccessible()) {
                throw new IllegalArgumentException(
                    "Cannot access configuration method " + method.toGenericString()
                );
            }
            return method.invoke(configuration);
        } catch (IllegalAccessException exception) {
            throw new IllegalArgumentException(
                "Cannot access configuration method " + method.toGenericString(),
                exception
            );
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(
                "Configuration method " + method.toGenericString() + " failed",
                cause
            );
        }
    }
}
