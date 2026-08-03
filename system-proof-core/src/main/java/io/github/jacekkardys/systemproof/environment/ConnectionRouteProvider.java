package io.github.jacekkardys.systemproof.environment;

/**
 * Prepares one typed consumer route from one exact runtime connection context.
 *
 * <p>The runtime invokes a provider independently for every matching connection. Implementations
 * receive immutable semantic metadata, a connection-bound observation capability, and the direct
 * endpoint published by the provider. They may therefore retain per-connection resources without
 * sharing endpoint state across consumers. Once this method returns, the installation transaction
 * owns the returned route and its resource. A successful batch commit transfers that ownership to
 * the runtime connection; a failed preparation or installation closes it during reverse-order
 * rollback. The provider remains responsible for cleaning resources created before it returns a
 * {@link ConnectionRoute}, because the framework cannot take ownership before receiving that
 * object.
 */
@FunctionalInterface
public interface ConnectionRouteProvider<C> {
    ConnectionRoute<C> prepare(ConnectionRouteContext<C> context);
}
