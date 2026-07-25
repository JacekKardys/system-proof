package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ConnectionId;

/** Closed category of framework-owned immutable scenario failure values. */
public sealed interface FailureEvent extends ScenarioEvent permits
    FailureEvent.EnvironmentStartup,
    FailureEvent.ComponentStartup,
    FailureEvent.ComponentCleanup,
    FailureEvent.ConnectionMaterialization,
    FailureEvent.ConnectionCleanup,
    FailureEvent.DriverResourceCleanup {
    FailureDetails failure();

    /** The environment could not complete startup. */
    record EnvironmentStartup(FailureDetails failure) implements FailureEvent {
        public EnvironmentStartup {
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** One component could not complete startup. */
    record ComponentStartup(
        ComponentId componentId,
        FailureDetails failure
    ) implements FailureEvent {
        public ComponentStartup {
            componentId = Objects.requireNonNull(componentId, "componentId must not be null");
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** One component resource could not complete cleanup. */
    record ComponentCleanup(
        ComponentId componentId,
        FailureDetails failure
    ) implements FailureEvent {
        public ComponentCleanup {
            componentId = Objects.requireNonNull(componentId, "componentId must not be null");
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** A provider could not materialize the direct target for one runtime connection. */
    record ConnectionMaterialization(
        ConnectionId connectionId,
        FailureDetails failure
    ) implements FailureEvent {
        public ConnectionMaterialization {
            connectionId = Objects.requireNonNull(
                connectionId,
                "connectionId must not be null"
            );
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** A provider cleanup failed after its runtime connection was made unavailable. */
    record ConnectionCleanup(
        ConnectionId connectionId,
        FailureDetails failure
    ) implements FailureEvent {
        public ConnectionCleanup {
            connectionId = Objects.requireNonNull(
                connectionId,
                "connectionId must not be null"
            );
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /** One named environment-scoped driver resource could not complete cleanup. */
    record DriverResourceCleanup(
        String resourceName,
        FailureDetails failure
    ) implements FailureEvent {
        public DriverResourceCleanup {
            resourceName = requireText(resourceName, "resourceName");
            failure = Objects.requireNonNull(failure, "failure must not be null");
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
