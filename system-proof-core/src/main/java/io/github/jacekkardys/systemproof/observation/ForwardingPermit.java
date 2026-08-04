package io.github.jacekkardys.systemproof.observation;

/**
 * Per-interaction handshake between the environment decision boundary and its gateway.
 *
 * <p>The gateway waits before writing any byte, performs at most one write/flush attempt after a
 * {@link ForwardingDecision#FORWARD} result, and reports exactly one terminal outcome. No original
 * bytes, socket, stream, or mutable buffer crosses this boundary.
 */
public interface ForwardingPermit {
    /** Waits for either explicit forwarding authorization or a terminal session-close decision. */
    ForwardingDecision awaitDecision() throws InterruptedException;

    /** Reports that the single write/flush attempt completed successfully. */
    void forwarded();

    /** Reports that the single write/flush attempt failed; the gateway must not retry it. */
    void writeFailed();

    /** Reports that the session can no longer retain or forward this interaction. */
    void abandoned();
}
