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
    /** Legacy immediate-decision hook for coordinators that do not retain an interaction. */
    ForwardingDecision decide(InteractionRef interactionRef);

    /**
     * Creates the per-interaction forwarding handshake.
     *
     * <p>Semantic coordinators override this method. Immediate coordinators inherit the adapter
     * around their legacy decision without retaining the recorded evidence.
     */
    default ForwardingPermit permit(RecordedInteraction interaction) {
        java.util.Objects.requireNonNull(interaction, "interaction must not be null");
        ForwardingDecision decision = java.util.Objects.requireNonNull(
            decide(interaction.interactionRef()),
            "Interaction coordinator returned null decision"
        );
        return new ForwardingPermit() {
            @Override
            public ForwardingDecision awaitDecision() {
                return decision;
            }

            @Override
            public void forwarded() {}

            @Override
            public void writeFailed() {}

            @Override
            public void abandoned() {}
        };
    }
}
