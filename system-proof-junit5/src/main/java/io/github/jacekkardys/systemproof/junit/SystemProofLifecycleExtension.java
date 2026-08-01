package io.github.jacekkardys.systemproof.junit;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.model.Environment;
import java.lang.reflect.Method;
import java.util.Arrays;
import lombok.val;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

public class SystemProofLifecycleExtension implements BeforeEachCallback, AfterEachCallback {

    private final EnvironmentDefinitionLocator definitionLocator = new EnvironmentDefinitionLocator();

    @Override
    public void beforeEach(ExtensionContext context) {
        val environmentDeclaration = findEnvironmentAnnotationInstance(context);
        val environmentDefinition = definitionLocator.locate(environmentDeclaration.environment());

        validateTestParameter(context.getRequiredTestMethod(), environmentDefinition.environmentType());

        try {
            val environment = environmentDefinition
                .invoke()
                .start();

            SystemProofSharedContext.of(context).putEnvironment(environment);

        } catch (EnvironmentStartException failure) {
            reportDiagnostics(context, failure.diagnostics(), failure);
            throw failure;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Environment environment = SystemProofSharedContext.of(context).removeEnvironment();
        Throwable testFailure = SystemProofSharedContext.of(context).removeTestFailure();

        if (testFailure == null) {
            testFailure = context.getExecutionException().orElse(null);
        }
        if (environment == null) {
            return;
        }

        if (testFailure != null) {
            EnvironmentDiagnostics diagnostics = captureDiagnostics(context, environment, testFailure);
            Throwable closeFailure = close(environment);
            if (closeFailure != null) {
                testFailure.addSuppressed(closeFailure);
            }
            if (diagnostics != null) {
                reportDiagnostics(context, diagnostics, testFailure);
            }
            return;
        }

        Throwable closeFailure = close(environment);
        if (closeFailure != null) {
            reportEnvironmentDiagnostics(context, environment, closeFailure);
            rethrow(closeFailure);
        }
    }

    private static void reportEnvironmentDiagnostics(
        ExtensionContext context,
        Environment environment,
        Throwable failure
    ) {
        EnvironmentDiagnostics diagnostics = captureDiagnostics(context, environment, failure);
        if (diagnostics != null) {
            reportDiagnostics(context, diagnostics, failure);
        }
    }

    private static Throwable close(Environment environment) {
        try {
            environment.close();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) failure;
    }

    private static SystemProof findEnvironmentAnnotationInstance(ExtensionContext context) {
        return AnnotationSupport
            .findAnnotation(context.getRequiredTestClass(), SystemProof.class)
            .orElseThrow(() -> new IllegalStateException(
                "EnvironmentTestExtension requires @EnvironmentTest on class '"
                    + context.getRequiredTestClass().getName() + "'"
            ));
    }

    private static void validateTestParameter(Method testMethod, Class<? extends Environment> environmentType) {
        long parameters = Arrays.stream(testMethod.getParameterTypes())
            .filter(environmentType::equals)
            .count();
        if (parameters != 1) {
            throw new IllegalStateException(
                "Test method '" + testMethod.getDeclaringClass().getName() + "#" + testMethod.getName()
                    + "' must declare exactly one " + environmentType.getName() + " environment parameter"
            );
        }
    }

    private static void reportDiagnostics(
        ExtensionContext context,
        EnvironmentDiagnostics diagnostics,
        Throwable failure
    ) {
        try {
            var artifact = EnvironmentDiagnosticsWriter.write(context.getRequiredTestMethod(), diagnostics);
            context.publishReportEntry("environment.diagnostics", artifact.toString());
        } catch (java.io.IOException | RuntimeException diagnosticsFailure) {
            failure.addSuppressed(diagnosticsFailure);
            context.publishReportEntry(
                "environment.diagnostics.error",
                "Could not write environment diagnostics: " + diagnosticsFailure
            );
        }
    }

    private static EnvironmentDiagnostics captureDiagnostics(
        ExtensionContext context,
        Environment environment,
        Throwable failure
    ) {
        try {
            return environment.diagnostics();
        } catch (RuntimeException | Error diagnosticsFailure) {
            failure.addSuppressed(diagnosticsFailure);
            context.publishReportEntry(
                "environment.diagnostics.error",
                "Could not collect environment diagnostics: " + diagnosticsFailure
            );
            return null;
        }
    }
}
