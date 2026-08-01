package io.github.jacekkardys.systemproof.junit;

import io.github.jacekkardys.systemproof.model.Environment;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

@NoArgsConstructor
public class SystemProofJUnitExceptionFactory {

    public static ParameterResolutionException environmentParameterNotFound() {
        return new ParameterResolutionException("Environment parameter not found");
    }

    public static ParameterResolutionException environmentParameterNotResolved(ParameterContext parameterContext, Environment environment) {
        return new ParameterResolutionException(
            "Environment %s cannot resolve parameter %s"
                .formatted(
                    environment.getClass().getName(),
                    parameterContext.getParameter().getType().getName()
                ));
    }
}
