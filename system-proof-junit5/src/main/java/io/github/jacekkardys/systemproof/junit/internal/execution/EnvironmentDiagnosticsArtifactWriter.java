package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.val;

/** Persists rendered environment diagnostics as a scenario artifact. */
final class EnvironmentDiagnosticsArtifactWriter {
    static final String ARTIFACTS_DIRECTORY_PROPERTY = "system.proof.artifacts";

    Path write(Method testMethod, EnvironmentDiagnostics diagnostics) throws IOException {
        val root = Path.of(System.getProperty(
            ARTIFACTS_DIRECTORY_PROPERTY,
            "target/system-proof-artifacts"
        ));
        return write(root, testMethod, diagnostics);
    }

    Path write(
        Path root,
        Method testMethod,
        EnvironmentDiagnostics diagnostics
    ) throws IOException {
        val scenario = testMethod.getDeclaringClass().getSimpleName() + "-" + testMethod.getName();
        val artifact = root.resolve(sanitize(scenario)).resolve("environment.log");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, diagnostics.content(), StandardCharsets.UTF_8);
        return artifact.toAbsolutePath().normalize();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
