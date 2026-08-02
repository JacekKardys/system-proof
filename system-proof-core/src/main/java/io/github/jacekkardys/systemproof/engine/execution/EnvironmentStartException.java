package io.github.jacekkardys.systemproof.engine.execution;

import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;

/** Preserves the startup cause with diagnostics captured after cleanup and final STOPPED state. */
public final class EnvironmentStartException extends RuntimeException {
    private final EnvironmentDiagnostics diagnostics;

    public EnvironmentStartException(Throwable cause, EnvironmentDiagnostics diagnostics) {
        super("Environment startup failed", Objects.requireNonNull(cause, "cause must not be null"));
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    public EnvironmentDiagnostics diagnostics() {
        return diagnostics;
    }
}
