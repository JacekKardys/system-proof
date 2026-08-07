package io.github.jacekkardys.systemproof.junit.internal;

import java.io.IOException;
import io.github.jacekkardys.systemproof.environment.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import lombok.val;

/** Coordinates best-effort secret-safe diagnostics. */
final class EnvironmentDiagnosticsReporter {

    private final EnvironmentDiagnosticsArtifactWriter artifactWriter =
        new EnvironmentDiagnosticsArtifactWriter();

    void onStartFailure(
        SystemProofSharedContext context,
        EnvironmentStartException failure
    ) {
        report(context, failure.diagnostics(), failure);
    }

    void beforeClose(SystemProofSharedContext context, Environment environment) {
        context.executionException().ifPresent(primaryFailure -> {
            val diagnostics = capture(context, environment, primaryFailure);
            if (diagnostics != null) {
                context.putPendingDiagnostics(diagnostics);
            }
        });
    }

    void afterClose(
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
                "capture-safe-environment-diagnostics",
                diagnosticsFailure,
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
            artifactWriter.write(context.requiredTestMethod(), diagnostics);
            context.publishReportEntry("environment.diagnostics", "environment.log");
        } catch (IOException | RuntimeException | Error diagnosticsFailure) {
            SystemProofLifecycleFailureAdapter.suppress(primaryFailure, diagnosticsFailure);
            publishError(
                context,
                "write-safe-environment-diagnostics",
                diagnosticsFailure,
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
        String operation,
        Throwable failure,
        Throwable primaryFailure
    ) {
        String safeValue = "operation=" + operation + "; failureType="
            + FailureDetails.from(failure).failureType();
        try {
            context.publishReportEntry("environment.diagnostics.error", safeValue);
        } catch (RuntimeException | Error publicationFailure) {
            if (primaryFailure != null) {
                SystemProofLifecycleFailureAdapter.suppress(
                    primaryFailure,
                    publicationFailure
                );
            }
        }
    }
}
