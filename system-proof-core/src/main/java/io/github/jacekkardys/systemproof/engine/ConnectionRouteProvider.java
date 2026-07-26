package io.github.jacekkardys.systemproof.engine;

import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.EndpointBinding;

/**
 * Prepares one typed consumer route from the direct endpoint published by its provider.
 *
 * <p>The runtime invokes a provider independently for every matching connection. Implementations
 * may therefore retain connection-owned resources without sharing endpoint state across consumers.
 * Once this method returns, the runtime owns the returned route and its resource. The provider
 * remains responsible for cleaning resources created before it returns a {@link ConnectionRoute},
 * because the runtime cannot take ownership before receiving that object.
 */
@FunctionalInterface
public interface ConnectionRouteProvider<C> {
    ConnectionRoute<C> prepare(
        ConnectionDescriptor connection,
        EndpointBinding<C> directTarget
    );
}
