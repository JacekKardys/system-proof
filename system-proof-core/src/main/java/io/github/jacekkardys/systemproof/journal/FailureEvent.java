package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;

/** Category for structured scenario failures. */
public non-sealed interface FailureEvent extends ScenarioEvent {
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
