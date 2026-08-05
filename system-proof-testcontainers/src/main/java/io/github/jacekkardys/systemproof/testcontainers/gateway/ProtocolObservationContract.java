package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Feature;

/**
 * Immutable adapter-provided observation profile for one protocol implementation.
 * Features are supported only when they are present in {@link #supportedFeatures()}.
 */
public record ProtocolObservationContract(
    String protocolId,
    String protocolScheme,
    Class<?> endpointType,
    EvidenceSchemaId evidenceSchema,
    Optional<EvidenceSchemaId> nativeFlowReferenceSchema,
    Set<Capability> capabilities,
    Set<Feature> supportedFeatures
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
        supportedFeatures = Set.copyOf(
            Objects.requireNonNull(supportedFeatures, "supportedFeatures must not be null")
        );
        if (capabilities.contains(Capability.CORRELATION_CONTRIBUTIONS)
            != nativeFlowReferenceSchema.isPresent()) {
            throw new IllegalArgumentException(
                "Correlation capability and native-flow reference schema must be declared together"
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
