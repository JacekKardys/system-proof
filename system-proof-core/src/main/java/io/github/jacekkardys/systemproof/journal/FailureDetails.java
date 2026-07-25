package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import java.util.Optional;

/** Immutable, throwable-free failure metadata safe to retain in a journal snapshot. */
public record FailureDetails(String failureType, Optional<String> message) {
    public FailureDetails {
        failureType = requireText(failureType, "failureType");
        message = Objects.requireNonNull(message, "message must not be null")
            .map(String::strip)
            .filter(value -> !value.isBlank());
    }

    public static FailureDetails from(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        String simpleName = failure.getClass().getSimpleName();
        String type = simpleName.isBlank() ? failure.getClass().getName() : simpleName;
        return new FailureDetails(type, Optional.ofNullable(failure.getMessage()));
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
