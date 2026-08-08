package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import io.github.jacekkardys.systemproof.environment.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentDiagnosticsReporterTest {

    private final EnvironmentDiagnosticsReporter diagnosticsReporter =
        new EnvironmentDiagnosticsReporter();

    @Test
    void shouldRetainArtifactAndPublicationFailuresAsSuppressed(@TempDir Path directory)
        throws Exception {
        Path blockedRoot = Files.writeString(directory.resolve("blocked-root"), "not a directory");
        String property = EnvironmentDiagnosticsArtifactWriter.ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, blockedRoot.toString());
        EnvironmentStartException primaryFailure = new EnvironmentStartException(
            new IllegalStateException("startup exploded"),
            diagnostics()
        );
        IllegalStateException publicationFailure = new IllegalStateException("publication exploded");

        try {
            assertThatCode(() -> diagnosticsReporter.onStartFailure(
                SystemProofSharedContext.of(failingPublicationContext(publicationFailure)),
                primaryFailure
            )).doesNotThrowAnyException();

            assertThat(primaryFailure.getSuppressed())
                .hasSize(2)
                .contains(publicationFailure);
            assertThat(primaryFailure.getSuppressed()[0])
                .hasMessageContaining("blocked-root");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void shouldPublishArtifactFailuresByFixedOperationAndTypeOnly(@TempDir Path directory)
        throws Exception {
        String secret = "junit-report-entry-exception-canary";
        Path blockedRoot = Files.writeString(directory.resolve("blocked-root"), secret);
        String property = EnvironmentDiagnosticsArtifactWriter.ARTIFACTS_DIRECTORY_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, blockedRoot.toString());
        EnvironmentStartException primaryFailure = new EnvironmentStartException(
            new IllegalStateException(secret),
            diagnostics()
        );
        Map<String, String> reportEntries = new HashMap<>();

        try {
            diagnosticsReporter.onStartFailure(
                SystemProofSharedContext.of(recordingContext(reportEntries)),
                primaryFailure
            );

            assertThat(primaryFailure.getSuppressed()).hasSize(1);
            assertThat(reportEntries)
                .hasSize(1)
                .containsKey("environment.diagnostics.error");
            assertThat(reportEntries.get("environment.diagnostics.error"))
                .startsWith("operation=write-safe-environment-diagnostics; failureType=")
                .doesNotContain(secret, blockedRoot.toString());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static ExtensionContext failingPublicationContext(RuntimeException publicationFailure)
        throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod("fails");
        return (ExtensionContext) Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getRequiredTestMethod" -> testMethod;
                case "publishReportEntry" -> throw publicationFailure;
                case "toString" -> "EnvironmentDiagnosticsReporterTestContext";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static ExtensionContext recordingContext(Map<String, String> reportEntries)
        throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod("fails");
        return (ExtensionContext) Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getRequiredTestMethod" -> testMethod;
                case "publishReportEntry" -> {
                    reportEntries.put((String) arguments[0], (String) arguments[1]);
                    yield null;
                }
                case "toString" -> "EnvironmentDiagnosticsReporterTestContext";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class Scenario {
        @SuppressWarnings("unused")
        void fails() {}
    }

    private static EnvironmentDiagnostics diagnostics() {
        return EnvironmentDiagnosticsTestFixture.capture();
    }
}
