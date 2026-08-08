package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

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

    /** A provider or route could not materialize one runtime connection's targets. */
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

    /** A connection-owned route or provider cleanup failed after consumer unavailability. */
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
            resourceName = requireResourceName(resourceName);
            failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    private static String requireResourceName(String value) {
        Objects.requireNonNull(value, "resourceName must not be null");
        if (value.length() > 128 || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException(
                "resourceName must be 1-128 ASCII identifier characters"
            );
        }
        return value;
    }
}
