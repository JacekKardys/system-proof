package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ConnectionId;

/**
 * Stable identity of one physical transport session on one logical connection.
 *
 * <p>The local value is allocated by the connection-scoped observation capability. It is scoped to
 * the connection and one environment execution; reconnecting creates a distinct session identity.
 * Its numeric value is an identity component, not evidence of time, ordering, or causality.
 */
public record SessionId(ConnectionId connectionId, long localValue) {
    public static final long FIRST_VALUE = 1L;

    public SessionId {
        connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        if (localValue < FIRST_VALUE) {
            throw new IllegalArgumentException(
                "Session localValue must be at least " + FIRST_VALUE
            );
        }
    }

    @Override
    public String toString() {
        return connectionId + "/session-" + localValue;
    }
}
