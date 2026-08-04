package io.github.jacekkardys.systemproof.observation;


/**
 * Environment-scoped decision boundary invoked after evidence has been recorded.
 *
 * <p>Implementations must be thread-safe. Matching and state transitions may be serialized, but an
 * implementation must not retain its coordinator lock while a gateway waits, writes, flushes, or
 * reports the forwarding result.
 */
@FunctionalInterface
public interface InteractionDecisionCoordinator {
    /**
     * Creates the per-interaction forwarding handshake.
     *
     * <p>This is the required SPI boundary. Gateways compiled against the earlier reference-only
     * decision contract must migrate explicitly instead of failing only when traffic arrives.
     */
    ForwardingPermit permit(RecordedInteraction interaction);
}
