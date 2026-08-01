package io.github.jacekkardys.systemproof.junit;

import static io.github.jacekkardys.systemproof.junit.SystemProofJUnitExceptionFactory.environmentParameterNotFound;
import static io.github.jacekkardys.systemproof.junit.SystemProofJUnitExceptionFactory.environmentParameterNotResolved;

import io.github.jacekkardys.systemproof.model.Environment;
import java.util.Objects;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class SystemProofParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();
        if (Objects.isNull(environment)) {
            return Boolean.FALSE;
        }

        return isEnvironmentParameter(parameterContext, environment);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).getEnvironment();

        if (Objects.isNull(environment)) {
            throw environmentParameterNotFound();
        }

        if (isEnvironmentParameter(parameterContext, environment)) {
            return environment;
        }

        throw environmentParameterNotResolved(parameterContext, environment);
    }

    private static boolean isEnvironmentParameter(ParameterContext parameterContext, Environment environment) {
        return parameterContext.getParameter().getType().isInstance(environment);
    }
}
