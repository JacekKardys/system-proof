package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.ForwardingPermit;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;

/** Default serialized environment coordinator for the immediate-forward milestone. */
final class ImmediateForwardDecisionCoordinator implements InteractionDecisionCoordinator {
    @Override
    public ForwardingPermit permit(RecordedInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        return ImmediatePermit.INSTANCE;
    }

    private enum ImmediatePermit implements ForwardingPermit {
        INSTANCE;

        @Override
        public ForwardingDecision awaitDecision() {
            return ForwardingDecision.FORWARD;
        }

        @Override
        public void forwarded() {}

        @Override
        public void writeFailed() {}

        @Override
        public void abandoned() {}
    }
}
