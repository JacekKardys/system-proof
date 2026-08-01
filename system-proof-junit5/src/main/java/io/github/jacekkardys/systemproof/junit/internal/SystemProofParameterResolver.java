package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.model.Environment;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Internal callback injecting the environment owned by the current JUnit test invocation. */
public final class SystemProofParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();
        return environment != null && isEnvironmentParameter(parameterContext, environment);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();

        if (environment == null) {
            throw new ParameterResolutionException("Environment has not been started");
        }

        if (isEnvironmentParameter(parameterContext, environment)) {
            return environment;
        }

        throw new ParameterResolutionException(
            "Environment " + environment.getClass().getName() + " cannot resolve parameter "
                + parameterContext.getParameter().getType().getName()
        );
    }

    private static boolean isEnvironmentParameter(ParameterContext parameterContext, Environment environment) {
        return parameterContext.getParameter().getType().isInstance(environment);
    }
}
