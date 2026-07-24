package pl.gov.il.test.harness.junit;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;
import pl.gov.il.test.harness.diagnostics.EnvironmentDiagnostics;

/** Persists the shared environment event log under the current JUnit scenario. */
@UtilityClass
final class EnvironmentDiagnosticsWriter {
    static final String ARTIFACTS_DIRECTORY_PROPERTY = "environment.test.artifacts";

    static Path write(Method testMethod, EnvironmentDiagnostics diagnostics) throws IOException {
        Path root = Path.of(System.getProperty(ARTIFACTS_DIRECTORY_PROPERTY, "target/regression-artifacts"));
        return write(root, testMethod, diagnostics);
    }

    static Path write(Path root, Method testMethod, EnvironmentDiagnostics diagnostics) throws IOException {
        String scenario = testMethod.getDeclaringClass().getSimpleName() + "-" + testMethod.getName();
        Path artifact = root.resolve(sanitize(scenario)).resolve("environment.log");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, diagnostics.content(), StandardCharsets.UTF_8);
        return artifact.toAbsolutePath().normalize();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
