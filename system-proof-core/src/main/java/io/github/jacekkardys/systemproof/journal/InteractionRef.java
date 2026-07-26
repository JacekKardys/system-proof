package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.ConnectionId;

/**
 * Stable scenario identity of one observed protocol unit.
 *
 * <p>The reference structurally contains its connection-bound session, topological flow direction,
 * and stream-local ordinal. The ordinal is monotonic only within the same connection, session, and
 * direction. Ordinals from different connections, sessions, or directions must not be compared to
 * infer ordering or causality. Explicit causal relations belong to a later proof layer.
 */
public record InteractionRef(
    SessionId sessionId,
    FlowDirection direction,
    long ordinal
) {
    public static final long FIRST_ORDINAL = 1L;

    public InteractionRef {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        direction = Objects.requireNonNull(direction, "direction must not be null");
        if (ordinal < FIRST_ORDINAL) {
            throw new IllegalArgumentException(
                "Interaction ordinal must be at least " + FIRST_ORDINAL
            );
        }
    }

    public ConnectionId connectionId() {
        return sessionId.connectionId();
    }

    @Override
    public String toString() {
        return sessionId + "/" + direction + "/" + ordinal;
    }
}
