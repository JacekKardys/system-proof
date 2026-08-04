package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Feature;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Prerequisite;

/** Immutable adapter-provided observation profile for one protocol implementation. */
public record ProtocolObservationContract(
    String protocolId,
    String protocolScheme,
    Class<?> endpointType,
    EvidenceSchemaId evidenceSchema,
    Optional<EvidenceSchemaId> nativeFlowReferenceSchema,
    Set<Capability> capabilities,
    Set<Prerequisite> prerequisites,
    Set<Feature> unsupportedModes
) {
    public ProtocolObservationContract {
        protocolId = requireText(protocolId, "protocolId");
        protocolScheme = requireText(protocolScheme, "protocolScheme");
        endpointType = Objects.requireNonNull(endpointType, "endpointType must not be null");
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
        prerequisites = Set.copyOf(
            Objects.requireNonNull(prerequisites, "prerequisites must not be null")
        );
        unsupportedModes = Set.copyOf(
            Objects.requireNonNull(unsupportedModes, "unsupportedModes must not be null")
        );
        if (capabilities.contains(Capability.CORRELATION_CONTRIBUTIONS)
            != nativeFlowReferenceSchema.isPresent()) {
            throw new IllegalArgumentException(
                "Correlation capability and native-flow reference schema must be declared together"
            );
        }
        if (capabilities.contains(Capability.DURABLE_SUCCESS)
            && !prerequisites.contains(Prerequisite.EXACT_SESSION_DURABILITY)) {
            throw new IllegalArgumentException(
                "Durable success requires exact-session durability verification"
            );
        }
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
