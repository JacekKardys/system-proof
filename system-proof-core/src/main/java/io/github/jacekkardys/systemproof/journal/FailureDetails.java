package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;

/** Immutable type-only failure metadata safe to retain in a journal snapshot. */
public final class FailureDetails {
    private static final int MAX_FAILURE_TYPE_CHARACTERS = 128;
    private static final String FALLBACK_FAILURE_TYPE = "Throwable";
    private final String failureType;

    private FailureDetails(String failureType) {
        this.failureType = failureType;
    }

    /**
     * Creates metadata without consulting throwable messages, causes, suppressed failures, stack
     * traces, or {@code Throwable.toString()}.
     */
    public static FailureDetails from(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return new FailureDetails(normalizedType(failure.getClass()));
    }

    /** Returns a bounded normalized simple type classification. */
    public String failureType() {
        return failureType;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof FailureDetails details
                && failureType.equals(details.failureType);
    }

    @Override
    public int hashCode() {
        return failureType.hashCode();
    }

    @Override
    public String toString() {
        return "FailureDetails[failureType=" + failureType + "]";
    }

    private static String normalizedType(Class<?> failureClass) {
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
}
