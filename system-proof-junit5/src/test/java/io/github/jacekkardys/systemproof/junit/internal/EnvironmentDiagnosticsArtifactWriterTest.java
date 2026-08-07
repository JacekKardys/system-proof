package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jacekkardys.systemproof.environment.EnvironmentDiagnostics;

class EnvironmentDiagnosticsArtifactWriterTest {
    private final EnvironmentDiagnosticsArtifactWriter writer =
        new EnvironmentDiagnosticsArtifactWriter();

    @Test
    void shouldWriteEnvironmentDiagnosticsToAStableScenarioArtifact(@TempDir Path directory) throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod("shouldRecordDiagnostics");

        EnvironmentDiagnostics diagnostics = diagnostics();
        Path artifact = writer.write(directory, testMethod, diagnostics);

        assertThat(artifact)
            .isEqualTo(directory.resolve("Scenario-shouldRecordDiagnostics/environment.log").toAbsolutePath().normalize());
        assertThat(Files.readString(artifact)).isEqualTo(diagnostics.content());
    }

    private static final class Scenario {
        @SuppressWarnings("unused")
        void shouldRecordDiagnostics() {}
    }

    private static EnvironmentDiagnostics diagnostics() {
        return EnvironmentDiagnosticsTestFixture.capture();
    }
}
