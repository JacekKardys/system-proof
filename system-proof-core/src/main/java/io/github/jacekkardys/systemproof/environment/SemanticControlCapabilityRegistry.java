package io.github.jacekkardys.systemproof.environment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.control.SemanticHoldSelector;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Environment-owned index validating declared and materialized semantic-control capability. */
final class SemanticControlCapabilityRegistry {
    private final Map<ConnectionId, Entry> connections =
        new LinkedHashMap<>();

    synchronized void register(
        ConnectionId connectionId,
        Supplier<Availability> availability,
        Optional<RequiredObservationProfile> requiredObservationProfile
    ) {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        availability = Objects.requireNonNull(
            availability,
            "availability must not be null"
        );
        requiredObservationProfile = Objects.requireNonNull(
            requiredObservationProfile,
            "requiredObservationProfile must not be null"
        );
        if (connections.putIfAbsent(
            connectionId,
            new Entry(availability, requiredObservationProfile)
        ) != null) {
            throw new IllegalStateException(
                "Semantic-control capability was registered more than once for connection '"
                    + connectionId + "'"
            );
        }
    }

    synchronized void validateArm(SemanticHoldSelector<?> selector) {
        selector = Objects.requireNonNull(selector, "selector must not be null");
        ConnectionId connectionId = selector.connectionId();
        Entry entry = connections.get(connectionId);
        if (entry == null) {
            throw new IllegalArgumentException(
                "Connection '" + connectionId + "' is outside the environment"
            );
        }
        Availability current = Objects.requireNonNull(
            entry.availability().get(),
            "Semantic-control availability must not be null"
        );
        switch (current) {
            case DECLARED, AVAILABLE -> {}
            case UNSUPPORTED -> throw new IllegalArgumentException(
                "Connection '" + connectionId
                    + "' does not declare semantic-control capability"
            );
            case UNAVAILABLE -> throw new IllegalStateException(
                "Connection '" + connectionId
                    + "' does not currently have active semantic-control capability"
            );
        }
        RequiredObservationProfile profile = entry.requiredObservationProfile()
            .orElseThrow(() -> new IllegalArgumentException(
                "Connection '" + connectionId
                    + "' has no required observation profile for semantic control"
            ));
        if (!profile.capabilities().contains(Capability.SEMANTIC_CONTROL)) {
            throw new IllegalArgumentException(
                "Connection '" + connectionId
                    + "' does not require semantic-control capability"
            );
        }
        if (!profile.evidenceSchema().equals(selector.evidenceSchema())) {
            throw new IllegalArgumentException(
                "Semantic hold evidence schema does not match connection '"
                    + connectionId + "'"
            );
        }
        selector.nativeFlowReferenceSchema().ifPresent(schema -> {
            if (!profile.capabilities().contains(Capability.CORRELATION_CONTRIBUTIONS)
                || !profile.nativeFlowReferenceSchema().filter(schema::equals).isPresent()) {
                throw new IllegalArgumentException(
                    "Semantic hold native-flow schema does not match connection '"
                        + connectionId + "'"
                );
            }
        });
    }

    private record Entry(
        Supplier<Availability> availability,
        Optional<RequiredObservationProfile> requiredObservationProfile
    ) {
        private Entry {
            Objects.requireNonNull(availability, "availability must not be null");
            Objects.requireNonNull(
                requiredObservationProfile,
                "requiredObservationProfile must not be null"
            );
        }
    }

    enum Availability {
        DECLARED,
        AVAILABLE,
        UNSUPPORTED,
        UNAVAILABLE
    }
}
