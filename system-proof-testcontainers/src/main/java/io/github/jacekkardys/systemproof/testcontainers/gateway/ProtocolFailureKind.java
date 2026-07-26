package io.github.jacekkardys.systemproof.testcontainers.gateway;

/** Secret-safe classification of a protocol observation failure. */
public enum ProtocolFailureKind {
    MALFORMED_INPUT,
    UNSUPPORTED_NEGOTIATION,
    UNSUPPORTED_ENCRYPTION,
    AMBIGUOUS_FRAMING,
    DESYNCHRONIZATION,
    EXCESSIVE_FRAME_SIZE,
    EXCESSIVE_BUFFERED_BYTES
}
