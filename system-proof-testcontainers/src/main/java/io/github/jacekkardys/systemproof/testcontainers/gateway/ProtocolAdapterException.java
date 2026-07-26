package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Objects;

/** Explicit fail-closed signal from protocol framing or gateway buffer enforcement. */
public final class ProtocolAdapterException extends Exception {
    private final ProtocolFailureKind kind;

    public ProtocolAdapterException(ProtocolFailureKind kind, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public ProtocolFailureKind kind() {
        return kind;
    }
}
