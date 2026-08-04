package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.testcontainers.Testcontainers;
import io.github.jacekkardys.systemproof.environment.ConnectionRoute;
import io.github.jacekkardys.systemproof.environment.ConnectionRouteContext;
import io.github.jacekkardys.systemproof.environment.ConnectionRouteProvider;
import io.github.jacekkardys.systemproof.environment.SemanticControlRouteCapability;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;

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
    private static final ProtocolLimits DEFAULT_PROTOCOL_LIMITS =
        new ProtocolLimits(1024 * 1024, 2 * 1024 * 1024);

    private final HostPortExposure hostPortExposure;
    private final GatewayListenerFactory listenerFactory;
    private final ForwardingOutputDecorator forwardingOutputs;

    public InteractionGateway() {
        this(
            Testcontainers::exposeHostPorts,
            ServerSocketGatewayListener::open,
            ForwardingOutputDecorator.passthrough()
        );
    }

    InteractionGateway(HostPortExposure hostPortExposure) {
        this(
            hostPortExposure,
            ServerSocketGatewayListener::open,
            ForwardingOutputDecorator.passthrough()
        );
    }

    InteractionGateway(
        HostPortExposure hostPortExposure,
        GatewayListenerFactory listenerFactory
    ) {
        this(
            hostPortExposure,
            listenerFactory,
            ForwardingOutputDecorator.passthrough()
        );
    }

    InteractionGateway(
        HostPortExposure hostPortExposure,
        GatewayListenerFactory listenerFactory,
        ForwardingOutputDecorator forwardingOutputs
    ) {
        this.hostPortExposure = Objects.requireNonNull(
            hostPortExposure,
            "hostPortExposure must not be null"
        );
        this.listenerFactory = Objects.requireNonNull(
            listenerFactory,
            "listenerFactory must not be null"
        );
        this.forwardingOutputs = Objects.requireNonNull(
            forwardingOutputs,
            "forwardingOutputs must not be null"
        );
    }

    /**
     * Returns a typed route provider that creates one transparent listener per matched connection.
     */
    public <C> ConnectionRouteProvider<C> tcp(TcpEndpointAdapter<C> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        return context -> prepare(context, endpoints, null, null);
    }

    /**
     * Returns a route provider with bounded protocol-aware observation using default limits.
     */
    public <C, E> ConnectionRouteProvider<C> tcp(
        TcpEndpointAdapter<C> endpoints,
        ProtocolAdapter<E> protocolAdapter
    ) {
        return tcp(endpoints, protocolAdapter, DEFAULT_PROTOCOL_LIMITS);
    }

    /**
     * Returns a route provider with bounded protocol-aware observation.
     *
     * <p>The adapter is used only when the matching routing rule requests optional or required
     * observation. A disabled rule retains the transparent path. The returned provider declares
     * semantic-control capability only when selected by a required-observation routing rule.
     */
    public <C, E> ConnectionRouteProvider<C> tcp(
        TcpEndpointAdapter<C> endpoints,
        ProtocolAdapter<E> protocolAdapter,
        ProtocolLimits protocolLimits
    ) {
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        Objects.requireNonNull(protocolAdapter, "protocolAdapter must not be null");
        Objects.requireNonNull(protocolLimits, "protocolLimits must not be null");
        return (ConnectionRouteProvider<C> & SemanticControlRouteCapability) context ->
            prepare(context, endpoints, protocolAdapter, protocolLimits);
    }

    private <C, E> ConnectionRoute<C> prepare(
        ConnectionRouteContext<C> context,
        TcpEndpointAdapter<C> endpoints,
        ProtocolAdapter<E> configuredProtocolAdapter,
        ProtocolLimits configuredProtocolLimits
    ) {
        Objects.requireNonNull(context, "context must not be null");
        ConnectionDescriptor connection = context.connection();
        EndpointBinding<C> directTarget = context.directTarget();
        if (context.observationRequirement() == ObservationRequirement.REQUIRED
            && configuredProtocolAdapter == null) {
            throw new IllegalArgumentException(
                "Required observation route has no protocol adapter"
            );
        }
        if (context.observationRequirement() != ObservationRequirement.DISABLED
            && configuredProtocolAdapter != null) {
            validateObservationContract(
                connection,
                directTarget,
                configuredProtocolAdapter,
                context.requiredObservationProfile(),
                context.observationRequirement() == ObservationRequirement.REQUIRED
            );
        }
        InetSocketAddress target = endpoints.address(directTarget.external());
        ProtocolAdapter<E> effectiveProtocolAdapter =
            context.observationRequirement()
                == ObservationRequirement.DISABLED
                    ? null
                    : configuredProtocolAdapter;
        ProtocolLimits effectiveProtocolLimits =
            effectiveProtocolAdapter == null ? null : configuredProtocolLimits;
        GatewayRoute<E> route = GatewayRoute.open(
            connection.id(),
            target,
            CONNECT_TIMEOUT,
            SHUTDOWN_TIMEOUT,
            context.observationRequirement(),
            context.observations(),
            context.coordinator(),
            effectiveProtocolAdapter,
            effectiveProtocolLimits,
            listenerFactory,
            forwardingOutputs
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
            return ConnectionRoute.routed(consumerTarget, route, route);
        } catch (RuntimeException | Error failure) {
            closeAfterPreparationFailure(route, failure);
            throw failure;
        }
    }

    private static void validateObservationContract(
        ConnectionDescriptor connection,
        EndpointBinding<?> directTarget,
        ProtocolAdapter<?> adapter,
        Optional<RequiredObservationProfile> requiredObservationProfile,
        boolean requireSemanticControl
    ) {
        ProtocolObservationContract contract = adapter.observationContract()
            .orElseThrow(() -> new IllegalArgumentException(
                "Protocol adapter does not declare an observation contract"
            ));
        if (!connection.protocolId().equals(contract.protocolId())
            || !connection.protocolScheme().equals(contract.protocolScheme())) {
            throw incompatibleAdapter(connection, "protocol");
        }
        if (!connection.contractTypeName().equals(contract.endpointType().getName())
            || !contract.endpointType().isInstance(directTarget.internal())
            || !contract.endpointType().isInstance(directTarget.external())) {
            throw incompatibleAdapter(connection, "endpoint type");
        }
        if (!adapter.evidenceCodec().schemaId().equals(contract.evidenceSchema())) {
            throw incompatibleAdapter(connection, "evidence schema");
        }
        requiredObservationProfile.ifPresent(required -> {
            if (!required.evidenceSchema().equals(contract.evidenceSchema())) {
                throw incompatibleAdapter(connection, "required evidence schema");
            }
            required.nativeFlowReferenceSchema().ifPresent(schema -> {
                if (!contract.nativeFlowReferenceSchema().filter(schema::equals).isPresent()) {
                    throw incompatibleAdapter(connection, "required native-flow schema");
                }
            });
            if (!contract.capabilities().containsAll(required.capabilities())) {
                throw incompatibleAdapter(connection, "required capabilities");
            }
            if (!contract.prerequisites().containsAll(required.prerequisites())) {
                throw incompatibleAdapter(connection, "required prerequisites");
            }
            if (required.requiredFeatures().stream().anyMatch(
                contract.unsupportedModes()::contains
            )) {
                throw incompatibleAdapter(connection, "required protocol features");
            }
        });
        if (requiredObservationProfile.isEmpty()
            && requireSemanticControl
            && !contract.capabilities().contains(Capability.SEMANTIC_CONTROL)) {
            throw incompatibleAdapter(connection, "semantic-control capability");
        }
    }

    private static IllegalArgumentException incompatibleAdapter(
        ConnectionDescriptor connection,
        String mismatch
    ) {
        return new IllegalArgumentException(
            "Protocol adapter is incompatible with connection '" + connection.id()
                + "': " + mismatch + " mismatch"
        );
    }

    private void expose(ConnectionDescriptor connection, GatewayRoute<?> route) {
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
        GatewayRoute<?> route,
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
