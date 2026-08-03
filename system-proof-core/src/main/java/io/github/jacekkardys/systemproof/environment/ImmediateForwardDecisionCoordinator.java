package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.InteractionRef;

/** Default serialized environment coordinator for the immediate-forward milestone. */
final class ImmediateForwardDecisionCoordinator implements InteractionDecisionCoordinator {
    @Override
    public synchronized ForwardingDecision decide(InteractionRef interactionRef) {
        Objects.requireNonNull(interactionRef, "interactionRef must not be null");
        return ForwardingDecision.FORWARD;
    }
}
