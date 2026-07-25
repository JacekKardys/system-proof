package io.github.jacekkardys.systemproof.journal;

import java.util.Locale;
import java.util.Objects;

/** Stable semantic identity of one checkpoint or barrier. */
public record CheckpointId(String value) {
    public CheckpointId {
        value = requireIdentifier(value);
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "checkpoint id must not be null");
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.:/-]*")) {
            throw new IllegalArgumentException("Invalid checkpoint id: " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
