package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.model.ConnectionId;

/**
 * Protocol-neutral stable metadata for one interaction observation.
 *
 * <p>Direction is relative to the observing component.
 */
public record InteractionMetadata(
    Optional<ConnectionId> connectionId,
    Optional<Direction> direction
) {
    public InteractionMetadata {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        direction = Objects.requireNonNull(direction, "direction must not be null");
    }

    public static InteractionMetadata unscoped() {
        return new InteractionMetadata(Optional.empty(), Optional.empty());
    }

    public static InteractionMetadata onConnection(
        ConnectionId connectionId,
        Direction direction
    ) {
        return new InteractionMetadata(
            Optional.of(Objects.requireNonNull(connectionId, "connectionId must not be null")),
            Optional.of(Objects.requireNonNull(direction, "direction must not be null"))
        );
    }

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

}
