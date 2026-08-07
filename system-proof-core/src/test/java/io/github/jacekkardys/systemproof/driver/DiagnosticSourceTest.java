package io.github.jacekkardys.systemproof.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DiagnosticSourceTest {
    private static final String NAME_CANARY = "diagnostic-source-name-canary";

    @Test
    void shouldReplaceBoundedHostileNamesWithOpaqueStableIdentifiers() {
        String hostileName = NAME_CANARY + "\nzażółć-秘密";

        DiagnosticSource first = DiagnosticSource.redacted(
            hostileName,
            () -> "content",
            input -> input
        );
        DiagnosticSource second = DiagnosticSource.sensitive(hostileName, () -> "content");

        assertThat(first.sourceId())
            .matches("source-[0-9a-f]{16}")
            .isEqualTo(second.sourceId())
            .doesNotContain(NAME_CANARY, "zażółć", "秘密");
        assertThat(first.toString()).doesNotContain(NAME_CANARY, "zażółć", "秘密");
    }

    @Test
    void shouldRejectNullBlankAndOversizedNamesWithoutEchoingInput() {
        assertThatThrownBy(() -> DiagnosticSource.unsupported(null, () -> "content"))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("name must not be null");
        assertThatThrownBy(() -> DiagnosticSource.unsupported(" \n\t ", () -> "content"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Diagnostic source name must not be blank");
        assertThatThrownBy(() -> DiagnosticSource.unsupported(
            NAME_CANARY.repeat(20),
            () -> "content"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Diagnostic source name exceeds 128 characters")
            .hasMessageNotContaining(NAME_CANARY);
    }
}
