package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static io.github.jacekkardys.systemproof.model.EndpointBinding.binding;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import org.testcontainers.Testcontainers;
import io.github.jacekkardys.systemproof.engine.ConnectionRoute;
import io.github.jacekkardys.systemproof.engine.ConnectionRouteContext;
import io.github.jacekkardys.systemproof.engine.ConnectionRouteProvider;
import io.github.jacekkardys.systemproof.model.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.model.EndpointBinding;

/**
 * Creates connection-owned transparent TCP routes through the test JVM.
 *
 * <p>One gateway instance can prepare routes for several endpoint contract types. It retains no
 * routing registry: {@code ConnectionRouting} selects a typed provider for each logical
 * connection, and the corresponding {@code RuntimeConnection} owns the returned listener and
 * active sessions.
 */
public final class InteractionGateway {
    static final String CONTAINER_HOST = "host.testcontainers.internal";
    static final String TEST_HOST = "127.0.0.1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final HostPortExposure hostPortExposure;

    public InteractionGateway() {
        this(Testcontainers::exposeHostPorts);
    }

    InteractionGateway(HostPortExposure hostPortExposure) {
        this.hostPortExposure = Objects.requireNonNull(
            hostPortExposure,
            "hostPortExposure must not be null"
        );
    }

    /**
     * Returns a typed route provider that creates one transparent listener per matched connection.
     */
    public <C> ConnectionRouteProvider<C> tcp(TcpEndpointAdapter<C> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        return context -> prepare(context, endpoints);
    }

    private <C> ConnectionRoute<C> prepare(
        ConnectionRouteContext<C> context,
        TcpEndpointAdapter<C> endpoints
    ) {
        Objects.requireNonNull(context, "context must not be null");
        ConnectionDescriptor connection = context.connection();
        EndpointBinding<C> directTarget = context.directTarget();
        InetSocketAddress target = endpoints.address(directTarget.external());
        GatewayRoute route = GatewayRoute.open(
            connection.id(),
            target,
            CONNECT_TIMEOUT,
            SHUTDOWN_TIMEOUT
        );
        try {
            route.start();
            expose(connection, route);
            EndpointBinding<C> consumerTarget = binding(
                endpoints.replaceAddress(
                    directTarget.internal(),
                    CONTAINER_HOST,
                    route.listenerPort()
                ),
                endpoints.replaceAddress(
                    directTarget.external(),
                    TEST_HOST,
                    route.listenerPort()
                )
            );
            return ConnectionRoute.routed(consumerTarget, route);
        } catch (RuntimeException | Error failure) {
            closeAfterPreparationFailure(route, failure);
            throw failure;
        }
    }

    private void expose(ConnectionDescriptor connection, GatewayRoute route) {
        try {
            hostPortExposure.expose(route.listenerPort());
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException(
                "InteractionGateway could not expose its listener for connection '"
                    + connection.id() + "' through Testcontainers host routing. "
                    + "A local Docker Engine or Docker Desktop setup with "
                    + "host.testcontainers.internal support is required.",
                failure
            );
        }
    }

    private static void closeAfterPreparationFailure(
        GatewayRoute route,
        Throwable preparationFailure
    ) {
        try {
            route.close();
        } catch (Exception | Error cleanupFailure) {
            preparationFailure.addSuppressed(cleanupFailure);
        }
    }

    @FunctionalInterface
    interface HostPortExposure {
        void expose(int port);
    }
}
