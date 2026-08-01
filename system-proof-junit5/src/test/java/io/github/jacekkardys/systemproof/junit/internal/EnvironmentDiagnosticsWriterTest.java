package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;

class EnvironmentDiagnosticsWriterTest {
    @Test
    void shouldWriteEnvironmentDiagnosticsToAStableScenarioArtifact(@TempDir Path directory) throws Exception {
        Method testMethod = Scenario.class.getDeclaredMethod("shouldRecordDiagnostics");

        Path artifact = EnvironmentDiagnosticsWriter.write(
            directory,
            testMethod,
            EnvironmentDiagnostics.diagnostics("component diagnostics")
        );

        assertThat(artifact)
            .isEqualTo(directory.resolve("Scenario-shouldRecordDiagnostics/environment.log").toAbsolutePath().normalize());
        assertThat(Files.readString(artifact)).isEqualTo("component diagnostics");
    }

    private static final class Scenario {
        @SuppressWarnings("unused")
        void shouldRecordDiagnostics() {}
    }
}
