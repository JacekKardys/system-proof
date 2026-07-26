package io.github.jacekkardys.systemproof.engine;

/**
 * Prepares one typed consumer route from one exact runtime connection context.
 *
 * <p>The runtime invokes a provider independently for every matching connection. Implementations
 * receive immutable semantic metadata, a connection-bound observation capability, and the direct
 * endpoint published by the provider. They may therefore retain connection-owned resources without
 * sharing endpoint state across consumers. Once this method returns, the runtime owns the returned
 * route and its resource. The provider remains responsible for cleaning resources created before
 * it returns a {@link ConnectionRoute}, because the runtime cannot take ownership before receiving
 * that object.
 */
@FunctionalInterface
public interface ConnectionRouteProvider<C> {
    ConnectionRoute<C> prepare(ConnectionRouteContext<C> context);
}
