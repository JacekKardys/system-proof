package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.engine.execution.EnvironmentStartException;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.io.IOException;
import lombok.val;

/** Coordinates best-effort environment diagnostic capture, persistence, and JUnit reporting. */
public final class EnvironmentDiagnosticsReporter {

    private final EnvironmentDiagnosticsArtifactWriter artifactWriter =
        new EnvironmentDiagnosticsArtifactWriter();

    public void onStartFailure(
        SystemProofSharedContext context,
        EnvironmentStartException failure
    ) {
        report(context, failure.diagnostics(), failure);
    }

    public void beforeClose(SystemProofSharedContext context, Environment environment) {
        context.executionException().ifPresent(primaryFailure -> {
            val diagnostics = capture(context, environment, primaryFailure);
            if (diagnostics != null) {
                context.putPendingDiagnostics(diagnostics);
            }
        });
    }

    public void afterClose(
        SystemProofSharedContext context,
        Environment environment
    ) {
        val pending = context.removePendingDiagnostics();
        val closeFailure = context.removeLifecycleFailure();
        val testFailure = context.executionException();
        if (testFailure.isPresent()) {
            if (pending != null) {
                report(context, pending, testFailure.orElseThrow());
            }
            return;
        }

        if (closeFailure != null) {
            captureAndReport(context, environment, closeFailure);
        }
    }

    private static EnvironmentDiagnostics capture(
        SystemProofSharedContext context,
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
        SystemProofSharedContext context,
        EnvironmentDiagnostics diagnostics,
        Throwable primaryFailure
    ) {
        try {
            val artifact = artifactWriter.write(
                context.requiredTestMethod(),
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
        SystemProofSharedContext context,
        Environment environment,
        Throwable primaryFailure
    ) {
        val diagnostics = capture(context, environment, primaryFailure);
        if (diagnostics != null) {
            report(context, diagnostics, primaryFailure);
        }
    }

    private static void publishError(
        SystemProofSharedContext context,
        String message,
        Throwable primaryFailure
    ) {
        try {
            context.publishReportEntry("environment.diagnostics.error", message);
        } catch (RuntimeException | Error publicationFailure) {
            SystemProofLifecycleFailureAdapter.suppress(primaryFailure, publicationFailure);
        }
    }
}
