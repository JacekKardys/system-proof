package io.github.jacekkardys.systemproof.engine.execution;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;

/** Identity-based route-failure protection applied before durable journal publication. */
final class FailureRedactor {
    private final Map<Throwable, FailureDetails> protectedFailures = new IdentityHashMap<>();

    synchronized void protectRoutePreparation(
        ConnectionId connectionId,
        Throwable failure
    ) {
        protectRouteFailure("preparation", connectionId, failure);
    }

    synchronized void protectRouteCleanup(ConnectionId connectionId, Throwable failure) {
        protectRouteFailure("cleanup", connectionId, failure);
    }

    synchronized FailureDetails details(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        FailureDetails protectedFailure = protectedFailures.get(failure);
        return protectedFailure == null ? FailureDetails.from(failure) : protectedFailure;
    }

    private void protectRouteFailure(
        String stage,
        ConnectionId connectionId,
        Throwable failure
    ) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        String simpleName = failure.getClass().getSimpleName();
        String type = simpleName.isBlank() ? failure.getClass().getName() : simpleName;
        protectedFailures.putIfAbsent(
            failure,
            new FailureDetails(
                type,
                Optional.of(
                    "Route " + stage + " failed for connection '" + connectionId + "'"
                )
            )
        );
    }
}
