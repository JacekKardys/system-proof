package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;

/** Immutable type-only failure metadata safe to retain in a journal snapshot. */
public record FailureDetails(Class<? extends Throwable> failureClass) {
    private static final int MAX_FAILURE_TYPE_CHARACTERS = 128;
    private static final String FALLBACK_FAILURE_TYPE = "Throwable";

    public FailureDetails {
        failureClass = Objects.requireNonNull(
            failureClass,
            "failureClass must not be null"
        );
    }

    /**
     * Creates metadata without consulting throwable messages, causes, suppressed failures, stack
     * traces, or {@code Throwable.toString()}.
     */
    public static FailureDetails from(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return new FailureDetails(failure.getClass());
    }

    /** Returns a bounded normalized simple type classification. */
    public String failureType() {
        String candidate = failureClass.getSimpleName();
        if (candidate == null || candidate.isEmpty()) {
            return FALLBACK_FAILURE_TYPE;
        }
        StringBuilder normalized = new StringBuilder(
            Math.min(candidate.length(), MAX_FAILURE_TYPE_CHARACTERS)
        );
        for (int index = 0;
             index < candidate.length() && normalized.length() < MAX_FAILURE_TYPE_CHARACTERS;
             index++) {
            char character = candidate.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_' || character == '$') {
                normalized.append(character);
            }
        }
        return normalized.isEmpty() ? FALLBACK_FAILURE_TYPE : normalized.toString();
    }

    @Override
    public String toString() {
        return "FailureDetails[failureType=" + failureType() + "]";
    }
}
