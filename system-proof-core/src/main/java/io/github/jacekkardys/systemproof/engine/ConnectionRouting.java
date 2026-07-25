package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.RoutingMode;

/**
 * Runtime routing selection without changing logical topology declarations.
 *
 * <p>The default selection is direct. A routed selection applies only to connections whose typed
 * contract exactly matches the configured contract type.
 */
public final class ConnectionRouting {
    private static final ConnectionRouting DIRECT = new ConnectionRouting(null, null);

    private final Class<?> routedContractType;
    private final ConnectionRouteProvider<?> routedProvider;

    private ConnectionRouting(
        Class<?> routedContractType,
        ConnectionRouteProvider<?> routedProvider
    ) {
        this.routedContractType = routedContractType;
        this.routedProvider = routedProvider;
    }

    public static ConnectionRouting direct() {
        return DIRECT;
    }

    public static <C> ConnectionRouting routed(
        Class<C> contractType,
        ConnectionRouteProvider<C> provider
    ) {
        return new ConnectionRouting(
            Objects.requireNonNull(contractType, "contractType must not be null"),
            Objects.requireNonNull(provider, "provider must not be null")
        );
    }

    <C> Selection<C> select(Connection<C> connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        if (routedContractType == null
            || !routedContractType.equals(connection.from().contractType())) {
            return new Selection<>(
                RoutingMode.DIRECT,
                (descriptor, directTarget) -> ConnectionRoute.direct(directTarget)
            );
        }
        return routedSelection(connection);
    }

    @SuppressWarnings("unchecked")
    private <C> Selection<C> routedSelection(Connection<C> connection) {
        if (!routedContractType.equals(connection.from().contractType())) {
            throw new IllegalArgumentException(
                "Routed contract type does not match connection '" + connection.id() + "'"
            );
        }
        return new Selection<>(
            RoutingMode.ROUTED,
            (ConnectionRouteProvider<C>) routedProvider
        );
    }

    record Selection<C>(
        RoutingMode mode,
        ConnectionRouteProvider<C> provider
    ) {
        Selection {
            mode = Objects.requireNonNull(mode, "mode must not be null");
            provider = Objects.requireNonNull(provider, "provider must not be null");
        }
    }
}
