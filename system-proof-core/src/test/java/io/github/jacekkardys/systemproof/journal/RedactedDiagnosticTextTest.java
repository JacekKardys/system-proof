package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedactedDiagnosticTextTest {
    private static final String SECRET = "redaction-canary-password";

    @Test
    void shouldProvideContentAndTruncationValueSemanticsToJournalReadModels() {
        RedactedDiagnosticText first = RedactedDiagnosticText.redact(
            "first input",
            ignored -> "same retained content"
        );
        RedactedDiagnosticText second = RedactedDiagnosticText.redact(
            "second input",
            ignored -> "same retained content"
        );
        RedactedDiagnosticText differentContent = RedactedDiagnosticText.redact(
            "third input",
            ignored -> "different retained content"
        );
        RedactedDiagnosticText truncated = RedactedDiagnosticText.redact(
            "x".repeat(16 * 1024 + 1),
            ignored -> "same prefix"
        );
        RedactedDiagnosticText sameContentNotTruncated = RedactedDiagnosticText.redact(
            "short input",
            ignored -> truncated.content()
        );

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(RedactedDiagnosticText.class.getConstructors()).isEmpty();
        assertThat(first).isNotEqualTo(differentContent);
        assertThat(truncated.content()).isEqualTo(sameContentNotTruncated.content());
        assertThat(truncated.truncated()).isTrue();
        assertThat(sameContentNotTruncated.truncated()).isFalse();
        assertThat(truncated).isNotEqualTo(sameContentNotTruncated);

        DiagnosticEvent firstEvent = new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            first
        );
        DiagnosticEvent secondEvent = new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            second
        );
        JournalEntry firstEntry = new JournalEntry(
            new JournalSequence(1),
            Optional.of(Duration.ZERO),
            firstEvent
        );
        JournalEntry secondEntry = new JournalEntry(
            new JournalSequence(1),
            Optional.of(Duration.ZERO),
            secondEvent
        );
        assertThat(firstEvent).isEqualTo(secondEvent).hasSameHashCodeAs(secondEvent);
        assertThat(firstEntry).isEqualTo(secondEntry).hasSameHashCodeAs(secondEntry);
        assertThat(first.toString())
            .contains("length=", "truncated=false")
            .doesNotContain("same retained content");
        assertThat(firstEvent.toString()).doesNotContain("same retained content");
        assertThat(firstEntry.toString()).doesNotContain("same retained content");
    }

    @Test
    void shouldFailClosedWhenSanitizerThrowsOrReturnsNullOrBlank() {
        List<RedactedDiagnosticText> results = List.of(
            RedactedDiagnosticText.redact(
                SECRET,
                input -> { throw new IllegalStateException(SECRET); }
            ),
            RedactedDiagnosticText.redact(SECRET, input -> null),
            RedactedDiagnosticText.redact(SECRET, input -> " \n\t ")
        );

        assertThat(results)
            .extracting(RedactedDiagnosticText::content)
            .containsExactly(
                "[DIAGNOSTIC OMITTED: SANITIZER_FAILED]",
                "[DIAGNOSTIC OMITTED: SANITIZER_RETURNED_NULL]",
                "[DIAGNOSTIC OMITTED: SANITIZER_RETURNED_BLANK]"
            );
        assertThat(results)
            .allSatisfy(result -> {
                assertThat(result.content()).doesNotContain(SECRET);
                assertThat(result.toString()).doesNotContain(SECRET);
            });
    }

    @Test
    void shouldBoundHostileInputBeforeSanitizingAndBoundMultilineOutput() {
        AtomicInteger observedInputLength = new AtomicInteger();
        String hostileInput = SECRET.repeat(100_000);
        String oversizedOutput = "sanitized-line" + System.lineSeparator();

        RedactedDiagnosticText result = RedactedDiagnosticText.redact(
            hostileInput,
            boundedInput -> {
                observedInputLength.set(boundedInput.length());
                return oversizedOutput.repeat(10_000);
            }
        );

        assertThat(observedInputLength).hasValue(16 * 1024);
        assertThat(result.content()).hasSizeLessThanOrEqualTo(4 * 1024);
        assertThat(result.content().lines().toList()).hasSizeLessThanOrEqualTo(64);
        assertThat(result.content()).endsWith("[TRUNCATED]").doesNotContain(SECRET);
        assertThat(result.truncated()).isTrue();
        assertThat(result.toString()).doesNotContain(SECRET, "sanitized-line");
    }

    @Test
    void shouldInspectOnlyBoundedSanitizerOutputBeforeClassifyingBlankText() {
        RedactedDiagnosticText result = RedactedDiagnosticText.redact(
            SECRET,
            ignored -> " ".repeat(1_000_000)
        );

        assertThat(result.content())
            .isEqualTo("[DIAGNOSTIC OMITTED: SANITIZER_RETURNED_BLANK]")
            .doesNotContain(SECRET);
    }
}
