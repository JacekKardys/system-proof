package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** Validates the environment injection contract of one System Proof test method. */
final class SystemProofTestParameterValidator {

    void validate(Method testMethod, Class<? extends Environment> environmentType) {
        Objects.requireNonNull(testMethod, "testMethod must not be null");
        Objects.requireNonNull(environmentType, "environmentType must not be null");

        val environmentParameters = Arrays.stream(testMethod.getParameterTypes())
            .filter(environmentType::equals)
            .count();
        val resolvableEnvironmentParameters = Arrays.stream(testMethod.getParameterTypes())
            .filter(parameterType -> parameterType.isAssignableFrom(environmentType))
            .count();
        if (environmentParameters != 1 || resolvableEnvironmentParameters != 1) {
            throw new ExtensionConfigurationException(
                "Test method '" + testMethod.getDeclaringClass().getName() + "#" + testMethod.getName()
                    + "' must declare exactly one " + environmentType.getName()
                    + " environment parameter and no other parameter assignable from that type"
            );
        }
    }
}
