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
    ProofRequirementDescriptor descriptor,
    ProofResolution resolution,
    ProofResolutionReason reason,
    Optional<ConnectionId> connectionId,
    List<InteractionRef> interactions
) {
    private static final int MAX_INTERACTIONS = 2;

    public ProofObligationResolution {
        id = Objects.requireNonNull(id, "id must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (descriptor.kind() != kind) {
            throw new IllegalArgumentException(
                "descriptor kind must match the resolved requirement kind"
            );
        }
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
        validateCompatibility(descriptor, resolution, reason, connectionId, interactions);
    }

    private static void validateCompatibility(
        ProofRequirementDescriptor descriptor,
        ProofResolution resolution,
        ProofResolutionReason reason,
        Optional<ConnectionId> connectionId,
        List<InteractionRef> interactions
    ) {
        if (resolution == ProofResolution.NOT_EVALUATED
            || reason == ProofResolutionReason.NOT_EVALUATED_AFTER_TERMINAL_OUTCOME) {
            require(
                resolution == ProofResolution.NOT_EVALUATED
                    && reason
                        == ProofResolutionReason.NOT_EVALUATED_AFTER_TERMINAL_OUTCOME
                    && interactions.isEmpty(),
                "NOT_EVALUATED requires its exact terminal reason without interactions"
            );
            validateOptionalConnection(descriptor, connectionId);
            return;
        }
        if (reason == ProofResolutionReason.ACTIVATION_NOT_REACHED) {
            require(
                resolution == ProofResolution.UNREACHED && interactions.isEmpty(),
                "ACTIVATION_NOT_REACHED requires an unreached item without interactions"
            );
            validateOptionalConnection(descriptor, connectionId);
            return;
        }
        require(
            reason != ProofResolutionReason.DEADLINE_EXPIRED,
            "A proof deadline is an evaluation lifecycle gap, not an obligation resolution"
        );
        switch (descriptor) {
            case ProofRequirementDescriptor.Prerequisite value -> {
                require(connectionId.isEmpty() && interactions.isEmpty(),
                    "A prerequisite cannot retain connection or interaction provenance");
                boolean valid = switch (value.expectedStatus()) {
                    case SATISFIED -> resolution == ProofResolution.SATISFIED
                        && reason == ProofResolutionReason.PREREQUISITE_SATISFIED;
                    case UNSUPPORTED -> resolution == ProofResolution.UNSUPPORTED
                        && reason == ProofResolutionReason.PREREQUISITE_UNSUPPORTED;
                    case FAILED -> resolution == ProofResolution.FAILED
                        && reason == ProofResolutionReason.PREREQUISITE_FAILED;
                };
                require(valid, "Prerequisite status, resolution, and reason must agree");
            }
            case ProofRequirementDescriptor.Observation value -> {
                requireConnection(value.connectionId(), connectionId, interactions, false);
                require(
                    matches(resolution, reason,
                        ProofResolution.SATISFIED, ProofResolutionReason.OBSERVATION_ACTIVE,
                        ProofResolution.UNSUPPORTED, ProofResolutionReason.OBSERVATION_UNSUPPORTED,
                        ProofResolution.MISSING, ProofResolutionReason.OBSERVATION_LOST,
                        ProofResolution.FAILED, ProofResolutionReason.OBSERVATION_FAILED),
                    "Observation resolution and reason must agree"
                );
            }
            case ProofRequirementDescriptor.Correlation value -> {
                requireConnection(value.connectionId(), connectionId, interactions, true);
                boolean valid = resolution == ProofResolution.SATISFIED
                        && reason == ProofResolutionReason.CORRELATION_UNIQUE
                        && interactions.size() == 1
                    || resolution == ProofResolution.MISSING
                        && reason == ProofResolutionReason.CORRELATION_MISSING
                        && interactions.isEmpty()
                    || resolution == ProofResolution.AMBIGUOUS
                        && reason == ProofResolutionReason.CORRELATION_AMBIGUOUS
                        && interactions.isEmpty();
                require(valid, "Correlation resolution, reason, and provenance must agree");
            }
            case ProofRequirementDescriptor.HoldControl value -> {
                require(
                    value.expectedState()
                        == io.github.jacekkardys.systemproof.control.SemanticHoldState.FORWARDED,
                    "A hold-control proof descriptor requires FORWARDED"
                );
                requireConnection(value.connectionId(), connectionId, interactions, true);
                require(
                    matches(resolution, reason,
                        ProofResolution.SATISFIED,
                            ProofResolutionReason.CONTROL_REACHED_EXPECTED_STATE,
                        ProofResolution.UNREACHED, ProofResolutionReason.CONTROL_UNREACHED,
                        ProofResolution.TIMED_OUT, ProofResolutionReason.CONTROL_TIMED_OUT,
                        ProofResolution.AMBIGUOUS,
                            ProofResolutionReason.CONTROL_CORRELATION_INVALIDATED,
                        ProofResolution.MISSING, ProofResolutionReason.CONTROL_SESSION_ENDED,
                        ProofResolution.FAILED, ProofResolutionReason.CONTROL_FAILED),
                    "Hold-control resolution and reason must agree"
                );
            }
            case ProofRequirementDescriptor.GuardControl value ->
                validateGuardControl(value, resolution, reason, connectionId, interactions);
            case ProofRequirementDescriptor.HoldEvidence value -> {
                requireConnection(value.connectionId(), connectionId, interactions, true);
                require(
                    resolution == ProofResolution.SATISFIED
                        && reason == ProofResolutionReason.EVIDENCE_PRESENT
                        && interactions.size() == 1
                    || resolution == ProofResolution.MISSING
                        && reason == ProofResolutionReason.EVIDENCE_MISSING
                        && interactions.isEmpty(),
                    "Hold-evidence resolution, reason, and provenance must agree"
                );
            }
            case ProofRequirementDescriptor.GuardEvidence value -> {
                requireConnection(value.connectionId(), connectionId, interactions, true);
                require(
                    resolution == ProofResolution.SATISFIED
                        && reason == ProofResolutionReason.EVIDENCE_PRESENT
                        && interactions.size() == 1
                    || resolution == ProofResolution.MISSING
                        && reason == ProofResolutionReason.EVIDENCE_MISSING
                        && interactions.isEmpty(),
                    "Guard-evidence resolution, reason, and provenance must agree"
                );
            }
            case ProofRequirementDescriptor.CausalRelation value ->
                validateRelation(value, resolution, reason, connectionId, interactions);
        }
    }

    private static void validateGuardControl(
        ProofRequirementDescriptor.GuardControl descriptor,
        ProofResolution resolution,
        ProofResolutionReason reason,
        Optional<ConnectionId> connectionId,
        List<InteractionRef> interactions
    ) {
        require(
            descriptor.expectedState()
                == io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardState.SATISFIED,
            "A guard-control proof descriptor requires SATISFIED"
        );
        if (resolution == ProofResolution.VIOLATED) {
            require(
                reason == ProofResolutionReason.CAUSAL_RELATION_VIOLATED,
                "A violated guard requires CAUSAL_RELATION_VIOLATED"
            );
            requireViolationProvenance(
                descriptor.predecessorConnectionId(),
                descriptor.successorConnectionId(),
                connectionId,
                interactions
            );
            return;
        }
        require(connectionId.isEmpty(), "A non-violated cross-connection guard has no single connection");
        validateGuardInteractions(
            descriptor.predecessorConnectionId(),
            descriptor.successorConnectionId(),
            interactions
        );
        require(
            matches(resolution, reason,
                ProofResolution.SATISFIED,
                    ProofResolutionReason.CONTROL_REACHED_EXPECTED_STATE,
                ProofResolution.UNREACHED, ProofResolutionReason.CONTROL_UNREACHED,
                ProofResolution.TIMED_OUT, ProofResolutionReason.CONTROL_TIMED_OUT,
                ProofResolution.AMBIGUOUS,
                    ProofResolutionReason.CONTROL_CORRELATION_INVALIDATED,
                ProofResolution.MISSING, ProofResolutionReason.CONTROL_SESSION_ENDED,
                ProofResolution.FAILED, ProofResolutionReason.CONTROL_FAILED),
            "Guard-control resolution and reason must agree"
        );
        if (resolution == ProofResolution.SATISFIED) {
            require(interactions.size() == 2,
                "A satisfied guard requires exact predecessor and successor provenance");
        }
    }

    private static void validateRelation(
        ProofRequirementDescriptor.CausalRelation descriptor,
        ProofResolution resolution,
        ProofResolutionReason reason,
        Optional<ConnectionId> connectionId,
        List<InteractionRef> interactions
    ) {
        if (resolution == ProofResolution.VIOLATED) {
            require(
                reason == ProofResolutionReason.CAUSAL_RELATION_VIOLATED,
                "A violated relation requires CAUSAL_RELATION_VIOLATED"
            );
            requireViolationProvenance(
                descriptor.predecessorConnectionId(),
                descriptor.successorConnectionId(),
                connectionId,
                interactions
            );
            return;
        }
        require(connectionId.isEmpty(), "A causal relation has no single connection");
        validateGuardInteractions(
            descriptor.predecessorConnectionId(),
            descriptor.successorConnectionId(),
            interactions
        );
        require(
            resolution == ProofResolution.SATISFIED
                && reason == ProofResolutionReason.CAUSAL_RELATION_ESTABLISHED
                && interactions.size() == 2
            || resolution == ProofResolution.UNREACHED
                && reason == ProofResolutionReason.CAUSAL_RELATION_UNREACHED
                && interactions.isEmpty(),
            "Causal-relation resolution, reason, and provenance must agree"
        );
    }

    private static void requireViolationProvenance(
        ConnectionId predecessor,
        ConnectionId successor,
        Optional<ConnectionId> connectionId,
        List<InteractionRef> interactions
    ) {
        require(connectionId.filter(successor::equals).isPresent(),
            "A violated relation requires exact successor connection provenance");
        require(!interactions.isEmpty() && interactions.size() <= 2,
            "A violated relation requires its exact successor interaction");
        validateGuardInteractions(predecessor, successor, interactions);
        require(interactions.getLast().connectionId().equals(successor),
            "The decisive violated interaction must belong to the successor connection");
    }

    private static void validateGuardInteractions(
        ConnectionId predecessor,
        ConnectionId successor,
        List<InteractionRef> interactions
    ) {
        if (interactions.size() == 1) {
            require(interactions.getFirst().connectionId().equals(successor),
                "A single guard interaction must be the successor");
        } else if (interactions.size() == 2) {
            require(interactions.getFirst().connectionId().equals(predecessor)
                    && interactions.getLast().connectionId().equals(successor),
                "Guard interactions must retain predecessor then successor provenance");
        }
    }

    private static void validateOptionalConnection(
        ProofRequirementDescriptor descriptor,
        Optional<ConnectionId> connectionId
    ) {
        connectionId.ifPresent(value -> require(
            declaredConnections(descriptor).contains(value),
            "Retained connection contradicts the requirement descriptor"
        ));
    }

    private static List<ConnectionId> declaredConnections(
        ProofRequirementDescriptor descriptor
    ) {
        return switch (descriptor) {
            case ProofRequirementDescriptor.Prerequisite ignored -> List.of();
            case ProofRequirementDescriptor.Observation value -> List.of(value.connectionId());
            case ProofRequirementDescriptor.Correlation value -> List.of(value.connectionId());
            case ProofRequirementDescriptor.HoldControl value -> List.of(value.connectionId());
            case ProofRequirementDescriptor.GuardControl value -> List.of(
                value.predecessorConnectionId(), value.successorConnectionId());
            case ProofRequirementDescriptor.HoldEvidence value -> List.of(value.connectionId());
            case ProofRequirementDescriptor.GuardEvidence value -> List.of(value.connectionId());
            case ProofRequirementDescriptor.CausalRelation value -> List.of(
                value.predecessorConnectionId(), value.successorConnectionId());
        };
    }

    private static void requireConnection(
        ConnectionId expected,
        Optional<ConnectionId> actual,
        List<InteractionRef> interactions,
        boolean interactionsAllowed
    ) {
        require(actual.filter(expected::equals).isPresent(),
            "Resolution connection must match its descriptor");
        require(interactionsAllowed || interactions.isEmpty(),
            "This requirement cannot retain interaction provenance");
        require(interactions.stream().allMatch(value -> value.connectionId().equals(expected)),
            "Retained interactions must match the descriptor connection");
    }

    private static boolean matches(
        ProofResolution resolution,
        ProofResolutionReason reason,
        Object... pairs
    ) {
        for (int index = 0; index < pairs.length; index += 2) {
            if (resolution == pairs[index] && reason == pairs[index + 1]) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
