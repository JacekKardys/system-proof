package io.github.jacekkardys.systemproof.topology;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A typed, directional required-to-provided relationship without runtime addresses.
 *
 * <p>Publicly constructed instances are detached declarations. The validated
 * {@code EnvironmentTopology.of(...)} boundary verifies endpoint membership, deterministic
 * identity, and compatibility before a connection can reach environment execution.</p>
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class Connection<C> implements ConnectionRef {
    private final RequiredPort<C> from;
    private final ProvidedPort<C> to;
    private final ConnectionId id;
}
