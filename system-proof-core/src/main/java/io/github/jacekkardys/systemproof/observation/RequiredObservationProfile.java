package io.github.jacekkardys.systemproof.observation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable scenario-owned requirements for observation on one logical connection.
 *
 * <p>The profile contains only protocol-neutral schema identities and capabilities. A route
 * provider must compare it with the adapter-provided profile before opening traffic.
 */
public record RequiredObservationProfile(
    EvidenceSchemaId evidenceSchema,
    Optional<EvidenceSchemaId> nativeFlowReferenceSchema,
    Set<Capability> capabilities,
    Set<Feature> requiredFeatures
) {
    public enum Capability {
        CORRELATION_CONTRIBUTIONS,
        SEMANTIC_CONTROL
    }

    public enum Feature {
        ENCRYPTED_TRANSPORT,
        GENERAL_PIPELINING
    }

    public RequiredObservationProfile {
        evidenceSchema = Objects.requireNonNull(
            evidenceSchema,
            "evidenceSchema must not be null"
        );
        nativeFlowReferenceSchema = Objects.requireNonNull(
            nativeFlowReferenceSchema,
            "nativeFlowReferenceSchema must not be null"
        );
        capabilities = Set.copyOf(
            Objects.requireNonNull(capabilities, "capabilities must not be null")
        );
        requiredFeatures = Set.copyOf(
            Objects.requireNonNull(requiredFeatures, "requiredFeatures must not be null")
        );
        if (capabilities.contains(Capability.CORRELATION_CONTRIBUTIONS)
            != nativeFlowReferenceSchema.isPresent()) {
            throw new IllegalArgumentException(
                "Correlation capability and native-flow reference schema must be required together"
            );
        }
    }
}
