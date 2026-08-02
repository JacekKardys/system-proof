package io.github.jacekkardys.systemproof.junit.internal.extension;

import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentParameterValidator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofSharedContext;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.Method;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Internal callback injecting the environment owned by the current JUnit test invocation. */
public final class EnvironmentParameterResolver implements ParameterResolver {

    private final EnvironmentParameterValidator parameterValidator =
        new EnvironmentParameterValidator();

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        val sharedContext = SystemProofSharedContext.of(context);
        val runningEnvironment = sharedContext.getRunningEnvironment();
        return runningEnvironment != null
            && parameterContext.getDeclaringExecutable() instanceof Method
            && Environment.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        val sharedContext = SystemProofSharedContext.of(context);
        val runningEnvironment = sharedContext.getRunningEnvironment();

        if (runningEnvironment == null) {
            throw new ParameterResolutionException("Environment has not been started");
        }

        parameterValidator.validateResolution(
            parameterContext.getDeclaringExecutable(),
            runningEnvironment.declaredType()
        );
        return runningEnvironment.instance();
    }
}
