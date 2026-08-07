package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;

/**
 * Immutable bounded diagnostic text produced only through an explicit redaction policy.
 *
 * <p>The sanitizer sees at most 16 KiB of input. Retained output is limited to 4 KiB and 64
 * lines. Sanitizer failure, {@code null}, or blank output produces a fixed classification and
 * never falls back to the input. This is a bounded best-effort redaction boundary, not a claim
 * that an incorrectly implemented sanitizer removes every secret.
 */
public final class RedactedDiagnosticText {
    private static final int MAX_INPUT_CHARACTERS = 16 * 1024;
    private static final int MAX_OUTPUT_CHARACTERS = 4 * 1024;
    private static final int MAX_OUTPUT_LINES = 64;
    private static final String TRUNCATION_MARKER = "[TRUNCATED]";
    private static final String SANITIZER_FAILED =
        "[DIAGNOSTIC OMITTED: SANITIZER_FAILED]";
    private static final String SANITIZER_RETURNED_NULL =
        "[DIAGNOSTIC OMITTED: SANITIZER_RETURNED_NULL]";
    private static final String SANITIZER_RETURNED_BLANK =
        "[DIAGNOSTIC OMITTED: SANITIZER_RETURNED_BLANK]";

    private final String content;
    private final boolean truncated;

    private RedactedDiagnosticText(String content, boolean truncated) {
        this.content = content;
        this.truncated = truncated;
    }

    /** Applies the supplied policy to a bounded prefix and retains only bounded output. */
    public static RedactedDiagnosticText redact(String input, Sanitizer sanitizer) {
        Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        if (input == null) {
            return new RedactedDiagnosticText(SANITIZER_RETURNED_NULL, false);
        }
        String boundedInput = input.length() <= MAX_INPUT_CHARACTERS
            ? input
            : input.substring(0, MAX_INPUT_CHARACTERS);
        String sanitized;
        try {
            sanitized = sanitizer.sanitize(boundedInput);
        } catch (RuntimeException | Error failure) {
            return new RedactedDiagnosticText(SANITIZER_FAILED, false);
        }
        if (sanitized == null) {
            return new RedactedDiagnosticText(SANITIZER_RETURNED_NULL, false);
        }
        return bound(sanitized, input.length() > MAX_INPUT_CHARACTERS);
    }

    public String content() {
        return content;
    }

    public boolean truncated() {
        return truncated;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedactedDiagnosticText text)) {
            return false;
        }
        return truncated == text.truncated && content.equals(text.content);
    }

    @Override
    public int hashCode() {
        return 31 * content.hashCode() + Boolean.hashCode(truncated);
    }

    @Override
    public String toString() {
        return "RedactedDiagnosticText[length=" + content.length()
            + ", truncated=" + truncated + "]";
    }

    private static RedactedDiagnosticText bound(String sanitized, boolean inputTruncated) {
        StringBuilder retained = new StringBuilder(
            Math.min(sanitized.length(), MAX_OUTPUT_CHARACTERS)
        );
        int index = 0;
        int retainedLines = 1;
        boolean outputTruncated = false;
        while (index < sanitized.length() && retained.length() < MAX_OUTPUT_CHARACTERS) {
            char current = sanitized.charAt(index);
            if (current == '\r' || current == '\n') {
                int next = current == '\r'
                    && index + 1 < sanitized.length()
                    && sanitized.charAt(index + 1) == '\n'
                    ? index + 2
                    : index + 1;
                if (retainedLines == MAX_OUTPUT_LINES - 1) {
                    outputTruncated = next < sanitized.length();
                    index = next;
                    break;
                }
                String separator = System.lineSeparator();
                if (retained.length() + separator.length() > MAX_OUTPUT_CHARACTERS) {
                    outputTruncated = true;
                    break;
                }
                retained.append(separator);
                retainedLines++;
                index = next;
                continue;
            }
            retained.append(current);
            index++;
        }
        outputTruncated |= index < sanitized.length();
        String bounded = retained.toString();
        if (bounded.isBlank()) {
            return new RedactedDiagnosticText(SANITIZER_RETURNED_BLANK, false);
        }
        boolean truncated = inputTruncated || outputTruncated;
        if (truncated) {
            bounded = appendMarker(bounded);
        }
        return new RedactedDiagnosticText(bounded, truncated);
    }

    private static String appendMarker(String value) {
        int separatorCharacters = value.isEmpty() ? 0 : System.lineSeparator().length();
        int available = MAX_OUTPUT_CHARACTERS
            - separatorCharacters
            - TRUNCATION_MARKER.length();
        String prefix = value.length() <= available ? value : value.substring(0, available);
        return prefix + (prefix.isEmpty() ? "" : System.lineSeparator()) + TRUNCATION_MARKER;
    }

    /** Explicit user-supplied bounded redaction policy. */
    @FunctionalInterface
    public interface Sanitizer {
        String sanitize(String boundedInput);
    }
}
