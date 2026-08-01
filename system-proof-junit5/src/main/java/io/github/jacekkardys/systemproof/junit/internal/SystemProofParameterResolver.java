package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.Method;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Internal callback injecting the environment owned by the current JUnit test invocation. */
public final class SystemProofParameterResolver implements ParameterResolver {

    private final SystemProofParameterValidator parameterValidator =
        new SystemProofParameterValidator();

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();
        return environment != null
            && parameterContext.getDeclaringExecutable() instanceof Method
            && Environment.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();

        if (environment == null) {
            throw new ParameterResolutionException("Environment has not been started");
        }

        val environmentType = environment.getClass().asSubclass(Environment.class);
        parameterValidator.validateResolution(
            parameterContext.getDeclaringExecutable(),
            environmentType
        );
        return environment;
    }
}
