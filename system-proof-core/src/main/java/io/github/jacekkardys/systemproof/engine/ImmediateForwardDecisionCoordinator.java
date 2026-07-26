package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.journal.InteractionRef;

/** Default serialized environment coordinator for the immediate-forward milestone. */
final class ImmediateForwardDecisionCoordinator implements InteractionDecisionCoordinator {
    @Override
    public synchronized ForwardingDecision decide(InteractionRef interactionRef) {
        Objects.requireNonNull(interactionRef, "interactionRef must not be null");
        return ForwardingDecision.FORWARD;
    }
}
