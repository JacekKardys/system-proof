package pl.gov.il.test.harness.junit;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.platform.commons.support.AnnotationSupport;
import pl.gov.il.test.harness.diagnostics.EnvironmentDiagnostics;
import pl.gov.il.test.harness.engine.EnvironmentStartException;
import pl.gov.il.test.harness.model.Environment;

/** Starts, injects, diagnoses, and closes the environment declared by @EnvironmentTest. */
public final class EnvironmentTestExtension implements
    BeforeEachCallback,
    ParameterResolver,
    TestExecutionExceptionHandler,
    AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(EnvironmentTestExtension.class);
    private static final String ENVIRONMENT = "environment";
    private static final String TEST_FAILURE = "test-failure";
    private final EnvironmentDefinitionLocator definitionLocator = new EnvironmentDefinitionLocator();

    @Override
    public void beforeEach(ExtensionContext context) {
        EnvironmentTest declaration = AnnotationSupport
            .findAnnotation(context.getRequiredTestClass(), EnvironmentTest.class)
            .orElseThrow(() -> new IllegalStateException(
                "EnvironmentTestExtension requires @EnvironmentTest on class '"
                    + context.getRequiredTestClass().getName() + "'"
            ));

        EnvironmentDefinitionLocator.LocatedDefinition located =
            definitionLocator.locate(declaration.environment());
        validateTestParameter(context.getRequiredTestMethod(), located.environmentType());
        Environment environment = located.invoke();

        try {
            Environment started = environment.start();
            if (started != environment) {
                throw new IllegalStateException("Environment.start must return the same object");
            }
            store(context).put(ENVIRONMENT, environment);
        } catch (EnvironmentStartException failure) {
            reportDiagnostics(context, failure.diagnostics(), failure);
            throw failure;
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
        Environment environment = environment(context);
        return environment != null && parameterContext.getParameter().getType().isInstance(environment);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
        Environment environment = environment(context);
        if (environment == null) {
            throw new ParameterResolutionException("Environment has not been started");
        }
        if (!parameterContext.getParameter().getType().isInstance(environment)) {
            throw new ParameterResolutionException(
                "Environment " + environment.getClass().getName() + " cannot resolve parameter "
                    + parameterContext.getParameter().getType().getName()
            );
        }
        return environment;
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        store(context).put(TEST_FAILURE, throwable);
        throw throwable;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Environment environment = store(context).remove(ENVIRONMENT, Environment.class);
        Throwable testFailure = store(context).remove(TEST_FAILURE, Throwable.class);
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

    private static Throwable close(Environment environment) {
        try {
            environment.close();
            return null;
        } catch (RuntimeException | Error failure) {
            return failure;
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

    private static Environment environment(ExtensionContext context) {
        return store(context).get(ENVIRONMENT, Environment.class);
    }

    private static ExtensionContext.Store store(ExtensionContext context) {
        return context.getStore(NAMESPACE);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) failure;
    }
}
