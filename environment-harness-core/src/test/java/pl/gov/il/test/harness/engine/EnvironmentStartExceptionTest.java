package pl.gov.il.test.harness.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.gov.il.test.harness.diagnostics.EnvironmentDiagnostics.diagnostics;

import org.junit.jupiter.api.Test;

class EnvironmentStartExceptionTest {
    @Test
    void shouldPreserveCauseAndDiagnostics() {
        IllegalStateException cause = new IllegalStateException("driver failed");
        EnvironmentStartException failure = new EnvironmentStartException(cause, diagnostics("timeline"));

        assertThat(failure).hasCause(cause);
        assertThat(failure.diagnostics().content()).isEqualTo("timeline");
    }
}
