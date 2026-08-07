package io.github.jacekkardys.systemproof.journal;

import java.util.Locale;
import java.util.Objects;

/** Stable 1-128 character ASCII semantic identity of one disruption. */
public record DisruptionId(String value) {
    public DisruptionId {
        value = requireIdentifier(value);
    }

    private static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "disruption id must not be null");
        if (value.length() > 128 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.:/-]*")) {
            throw new IllegalArgumentException(
                "disruption id must be 1-128 ASCII identifier characters"
            );
        }
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
