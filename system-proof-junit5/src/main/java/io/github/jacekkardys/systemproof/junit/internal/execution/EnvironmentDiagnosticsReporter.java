package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.model.Environment;
import java.io.IOException;
import java.util.Optional;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Coordinates best-effort environment diagnostic capture, persistence, and JUnit reporting. */
public final class EnvironmentDiagnosticsReporter {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(EnvironmentDiagnosticsReporter.class);
    private static final String PENDING_DIAGNOSTICS = "pending-diagnostics";
    private final EnvironmentDiagnosticsArtifactWriter artifactWriter =
        new EnvironmentDiagnosticsArtifactWriter();

    public void onStartFailure(
        ExtensionContext context,
        EnvironmentStartException failure
    ) {
        report(context, failure.diagnostics(), failure);
    }

    public void beforeClose(ExtensionContext context, Environment environment) {
        context.getExecutionException().ifPresent(primaryFailure -> {
            val diagnostics = capture(context, environment, primaryFailure);
            if (diagnostics != null) {
                store(context).put(PENDING_DIAGNOSTICS, diagnostics);
            }
        });
    }

    public void afterClose(
        ExtensionContext context,
        Environment environment,
        Optional<Throwable> closeFailure
    ) {
        val pending = store(context).remove(
            PENDING_DIAGNOSTICS,
            EnvironmentDiagnostics.class
        );
        val testFailure = context.getExecutionException();
        if (testFailure.isPresent()) {
            if (pending != null) {
                report(context, pending, testFailure.orElseThrow());
            }
            return;
        }

        closeFailure.ifPresent(failure -> captureAndReport(context, environment, failure));
    }

    private static EnvironmentDiagnostics capture(
        ExtensionContext context,
        Environment environment,
        Throwable primaryFailure
    ) {
        try {
            return environment.diagnostics();
        } catch (RuntimeException | Error diagnosticsFailure) {
            SystemProofLifecycleFailureAdapter.suppress(primaryFailure, diagnosticsFailure);
            publishError(
                context,
                "Could not collect environment diagnostics: " + diagnosticsFailure,
                primaryFailure
            );
            return null;
        }
    }

    private void report(
        ExtensionContext context,
        EnvironmentDiagnostics diagnostics,
        Throwable primaryFailure
    ) {
        try {
            val artifact = artifactWriter.write(
                context.getRequiredTestMethod(),
                diagnostics
            );
            context.publishReportEntry("environment.diagnostics", artifact.toString());
        } catch (IOException | RuntimeException | Error diagnosticsFailure) {
            SystemProofLifecycleFailureAdapter.suppress(primaryFailure, diagnosticsFailure);
            publishError(
                context,
                "Could not write environment diagnostics: " + diagnosticsFailure,
                primaryFailure
            );
        }
    }

    private void captureAndReport(
        ExtensionContext context,
        Environment environment,
        Throwable primaryFailure
    ) {
        val diagnostics = capture(context, environment, primaryFailure);
        if (diagnostics != null) {
            report(context, diagnostics, primaryFailure);
        }
    }

    private static void publishError(
        ExtensionContext context,
        String message,
        Throwable primaryFailure
    ) {
        try {
            context.publishReportEntry("environment.diagnostics.error", message);
        } catch (RuntimeException | Error publicationFailure) {
            SystemProofLifecycleFailureAdapter.suppress(primaryFailure, publicationFailure);
        }
    }

    private static ExtensionContext.Store store(ExtensionContext context) {
        return context.getStore(NAMESPACE);
    }
}
