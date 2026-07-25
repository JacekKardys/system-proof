package io.github.jacekkardys.systemproof.journal;

import java.util.Locale;
import java.util.Objects;

/** Stable semantic identity of one disruption. */
public record DisruptionId(String value) {
    public DisruptionId {
        value = requireIdentifier(value);
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "disruption id must not be null");
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.:/-]*")) {
            throw new IllegalArgumentException("Invalid disruption id: " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
