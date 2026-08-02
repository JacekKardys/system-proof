package io.github.jacekkardys.systemproof.observation;

import io.github.jacekkardys.systemproof.observation.InteractionRef;

/**
 * Environment-scoped decision boundary invoked after evidence has been recorded.
 *
 * <p>Implementations must be thread-safe. The current environment implementation serializes calls
 * and always returns {@link ForwardingDecision#FORWARD}; later control work can extend the decision
 * model without moving the observe-before-forward boundary into protocol adapters.
 */
@FunctionalInterface
public interface InteractionDecisionCoordinator {
    ForwardingDecision decide(InteractionRef interactionRef);
}
