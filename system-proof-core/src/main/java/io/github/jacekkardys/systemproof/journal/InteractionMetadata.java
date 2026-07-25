package io.github.jacekkardys.systemproof.journal;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-neutral stable metadata for one interaction observation.
 *
 * <p>Direction is relative to the observing component.
 */
public record InteractionMetadata(
    Optional<String> connectionId,
    Optional<Direction> direction
) {
    public InteractionMetadata {
        connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        ).map(InteractionMetadata::requireConnectionId);
        direction = Objects.requireNonNull(direction, "direction must not be null");
    }

    public static InteractionMetadata unscoped() {
        return new InteractionMetadata(Optional.empty(), Optional.empty());
    }

    public static InteractionMetadata onConnection(
        String connectionId,
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

    private static String requireConnectionId(String value) {
        Objects.requireNonNull(value, "connectionId must not be null");
        if (!value.matches(
            "[a-zA-Z0-9][a-zA-Z0-9_.:/-]*(->[a-zA-Z0-9][a-zA-Z0-9_.:/-]*)?"
        )) {
            throw new IllegalArgumentException("Invalid connectionId: " + value);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
