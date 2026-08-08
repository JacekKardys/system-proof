package io.github.jacekkardys.systemproof.proof;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Detached typed resolution and bounded decisive provenance for one required plan item. */
public record ProofObligationResolution(
    ProofObligationId id,
    ProofRequirementKind kind,
    ProofResolution resolution,
    ProofResolutionReason reason,
    Optional<ConnectionId> connectionId,
    List<InteractionRef> interactions
) {
    private static final int MAX_INTERACTIONS = 2;

    public ProofObligationResolution {
        id = Objects.requireNonNull(id, "id must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        interactions = List.copyOf(
            Objects.requireNonNull(interactions, "interactions must not be null")
        );
        if (interactions.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("interactions must not contain null");
        }
        if (interactions.size() > MAX_INTERACTIONS) {
            throw new IllegalArgumentException(
                "A proof resolution retains at most " + MAX_INTERACTIONS
                    + " decisive interaction references"
            );
        }
    }
}
