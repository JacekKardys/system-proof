package io.github.jacekkardys.systemproof.topology;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A typed, directional required-to-provided relationship without runtime addresses.
 * Construction code is responsible for compatibility checks and deterministic identity creation.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class Connection<C> implements ConnectionRef {
    private final RequiredPort<C> from;
    private final ProvidedPort<C> to;
    private final ConnectionId id;
}
