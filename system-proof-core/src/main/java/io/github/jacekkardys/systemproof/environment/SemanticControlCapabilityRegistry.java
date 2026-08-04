package io.github.jacekkardys.systemproof.environment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Environment-owned index validating declared and materialized semantic-control capability. */
final class SemanticControlCapabilityRegistry {
    private final Map<ConnectionId, Supplier<Availability>> connections =
        new LinkedHashMap<>();

    synchronized void register(
        ConnectionId connectionId,
        Supplier<Availability> availability
    ) {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        availability = Objects.requireNonNull(
            availability,
            "availability must not be null"
        );
        if (connections.putIfAbsent(connectionId, availability) != null) {
            throw new IllegalStateException(
                "Semantic-control capability was registered more than once for connection '"
                    + connectionId + "'"
            );
        }
    }

    synchronized void validateArm(ConnectionId connectionId) {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        Supplier<Availability> availability = connections.get(connectionId);
        if (availability == null) {
            throw new IllegalArgumentException(
                "Connection '" + connectionId + "' is outside the environment"
            );
        }
        Availability current = Objects.requireNonNull(
            availability.get(),
            "Semantic-control availability must not be null"
        );
        switch (current) {
            case DECLARED, AVAILABLE -> {
                return;
            }
            case UNSUPPORTED -> throw new IllegalArgumentException(
                "Connection '" + connectionId
                    + "' does not declare semantic-control capability"
            );
            case UNAVAILABLE -> throw new IllegalStateException(
                "Connection '" + connectionId
                    + "' does not currently have active semantic-control capability"
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
