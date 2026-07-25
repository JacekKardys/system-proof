package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.LogLevel;

/** Free-form diagnostic text with a structured subject and severity. */
public record DiagnosticEvent(
    Subject subject,
    LogLevel level,
    String message
) implements ScenarioEvent {
    public DiagnosticEvent {
        subject = Objects.requireNonNull(subject, "subject must not be null");
        level = Objects.requireNonNull(level, "level must not be null");
        message = requireText(message, "message");
    }

    /** Stable subject of diagnostic text. */
    public sealed interface Subject permits
        EnvironmentSubject,
        ComponentSubject,
        ConnectionSubject {
    }

    /** The environment/framework diagnostic subject. */
    public enum EnvironmentSubject implements Subject {
        INSTANCE
    }

    /** A diagnostic subject identified by an immutable component identity. */
    public record ComponentSubject(ComponentId componentId) implements Subject {
        public ComponentSubject {
            componentId = Objects.requireNonNull(componentId, "componentId must not be null");
        }
    }

    /** A diagnostic subject identified by the stable logical connection identity. */
    public record ConnectionSubject(ConnectionId connectionId) implements Subject {
        public ConnectionSubject {
            connectionId = Objects.requireNonNull(
                connectionId,
                "connectionId must not be null"
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
