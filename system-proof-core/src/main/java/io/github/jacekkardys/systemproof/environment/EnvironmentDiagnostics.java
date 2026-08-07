package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;

/**
 * Secret-safe-by-policy runtime diagnostics produced only by an {@link Environment}.
 *
 * <p>The type deliberately has no public constructor or text factory. Raw and sensitive sources
 * have no framework export path and are never included in {@link #content()}.
 */
public final class EnvironmentDiagnostics {
    private final String content;

    EnvironmentDiagnostics(String content) {
        this.content = Objects.requireNonNull(content, "content must not be null");
    }

    public String content() {
        return content;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof EnvironmentDiagnostics diagnostics
                && content.equals(diagnostics.content);
    }

    @Override
    public int hashCode() {
        return content.hashCode();
    }

    @Override
    public String toString() {
        return "EnvironmentDiagnostics[characters=" + content.length() + "]";
    }
}
