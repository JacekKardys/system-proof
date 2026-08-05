package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.topology.Connection;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;

/**
 * Immutable runtime routing policy without changing logical topology declarations.
 *
 * <p>Rules match semantic contracts or one structured connection identity. Connection-specific
 * rules take precedence over contract-wide rules; unmatched connections remain direct.
 */
public final class ConnectionRouting {
    private static final ConnectionRouting DIRECT = new ConnectionRouting(List.of());

    private final List<Rule<?>> rules;

    private ConnectionRouting(List<Rule<?>> rules) {
        this.rules = List.copyOf(rules);
    }

    public static ConnectionRouting direct() {
        return DIRECT;
    }

    public static <C> ConnectionRouting routed(
        Contract<C> contract,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(contract, ObservationRequirement.DISABLED, provider);
    }

    public static <C> ConnectionRouting routed(
        Contract<C> contract,
        ObservationRequirement observationRequirement,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(contract, observationRequirement, provider);
    }

    /** Routes every matching contract with one explicit scenario-owned observation profile. */
    public static <C> ConnectionRouting routed(
        Contract<C> contract,
        RequiredObservationProfile requiredObservationProfile,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(contract, requiredObservationProfile, provider);
    }

    public static <C> ConnectionRouting routed(
        Connection<C> connection,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(connection, ObservationRequirement.DISABLED, provider);
    }

    public static <C> ConnectionRouting routed(
        Connection<C> connection,
        ObservationRequirement observationRequirement,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(connection, observationRequirement, provider);
    }

    /** Routes one exact connection with an explicit scenario-owned observation profile. */
    public static <C> ConnectionRouting routed(
        Connection<C> connection,
        RequiredObservationProfile requiredObservationProfile,
        ConnectionRouteProvider<C> provider
    ) {
        return DIRECT.withRoute(connection, requiredObservationProfile, provider);
    }

    public <C> ConnectionRouting withRoute(
        Contract<C> contract,
        ConnectionRouteProvider<C> provider
    ) {
        return withRoute(contract, ObservationRequirement.DISABLED, provider);
    }

    public <C> ConnectionRouting withRoute(
        Contract<C> contract,
        ObservationRequirement observationRequirement,
        ConnectionRouteProvider<C> provider
    ) {
        return append(Rule.forContract(contract, observationRequirement, provider));
    }

    public <C> ConnectionRouting withRoute(
        Contract<C> contract,
        RequiredObservationProfile requiredObservationProfile,
        ConnectionRouteProvider<C> provider
    ) {
        return append(Rule.forContract(
            contract,
            ObservationRequirement.REQUIRED,
            Objects.requireNonNull(
                requiredObservationProfile,
                "requiredObservationProfile must not be null"
            ),
            provider
        ));
    }

    public <C> ConnectionRouting withRoute(
        Connection<C> connection,
        ConnectionRouteProvider<C> provider
    ) {
        return withRoute(connection, ObservationRequirement.DISABLED, provider);
    }

    public <C> ConnectionRouting withRoute(
        Connection<C> connection,
        ObservationRequirement observationRequirement,
        ConnectionRouteProvider<C> provider
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        return append(Rule.forConnection(
            connection.id(),
            connection.from().contract(),
            observationRequirement,
            null,
            provider
        ));
    }

    public <C> ConnectionRouting withRoute(
        Connection<C> connection,
        RequiredObservationProfile requiredObservationProfile,
        ConnectionRouteProvider<C> provider
    ) {
        Objects.requireNonNull(connection, "connection must not be null");
        return append(Rule.forConnection(
            connection.id(),
            connection.from().contract(),
            ObservationRequirement.REQUIRED,
            Objects.requireNonNull(
                requiredObservationProfile,
                "requiredObservationProfile must not be null"
            ),
            provider
        ));
    }

    /** Resolves the immutable execution policy for one materialized connection. */
    <C> Selection<C> select(Connection<C> connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        Rule<?> rule = matchingRule(connection);
        if (rule == null) {
            return new Selection<>(
                RoutingMode.DIRECT,
                ObservationRequirement.DISABLED,
                null,
                context -> ConnectionRoute.direct(context.directTarget())
            );
        }
        return routedSelection(connection, rule);
    }

    private ConnectionRouting append(Rule<?> rule) {
        if (rules.stream().anyMatch(existing -> existing.sameSelector(rule))) {
            throw new IllegalArgumentException(
                "Routing rule already exists for " + rule.selectorDescription()
            );
        }
        List<Rule<?>> updated = new ArrayList<>(rules);
        updated.add(rule);
        return new ConnectionRouting(updated);
    }

    private Rule<?> matchingRule(Connection<?> connection) {
        Rule<?> connectionRule = rules.stream()
            .filter(rule -> rule.connectionId() != null)
            .filter(rule -> rule.matches(connection))
            .findFirst()
            .orElse(null);
        if (connectionRule != null) {
            return connectionRule;
        }
        return rules.stream()
            .filter(rule -> rule.connectionId() == null)
            .filter(rule -> rule.matches(connection))
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <C> Selection<C> routedSelection(
        Connection<C> connection,
        Rule<?> rule
    ) {
        if (!rule.matches(connection)
            || !rule.contract().equals(connection.from().contract())) {
            throw new IllegalArgumentException(
                "Routing rule does not match connection '" + connection.id() + "'"
            );
        }
        return new Selection<>(
            RoutingMode.ROUTED,
            rule.observationRequirement(),
            rule.requiredObservationProfile(),
            (ConnectionRouteProvider<C>) rule.provider()
        );
    }

    private record Rule<C>(
        Contract<C> contract,
        ConnectionId connectionId,
        ObservationRequirement observationRequirement,
        RequiredObservationProfile requiredObservationProfile,
        ConnectionRouteProvider<C> provider
    ) {
        private Rule {
            contract = Objects.requireNonNull(contract, "contract must not be null");
            observationRequirement = Objects.requireNonNull(
                observationRequirement,
                "observationRequirement must not be null"
            );
            provider = Objects.requireNonNull(provider, "provider must not be null");
        }

        private static <C> Rule<C> forContract(
            Contract<C> contract,
            ObservationRequirement observationRequirement,
            ConnectionRouteProvider<C> provider
        ) {
            return forContract(contract, observationRequirement, null, provider);
        }

        private static <C> Rule<C> forContract(
            Contract<C> contract,
            ObservationRequirement observationRequirement,
            RequiredObservationProfile requiredObservationProfile,
            ConnectionRouteProvider<C> provider
        ) {
            return new Rule<>(
                contract,
                null,
                observationRequirement,
                requiredObservationProfile,
                provider
            );
        }

        private static <C> Rule<C> forConnection(
            ConnectionId connectionId,
            Contract<C> contract,
            ObservationRequirement observationRequirement,
            RequiredObservationProfile requiredObservationProfile,
            ConnectionRouteProvider<C> provider
        ) {
            return new Rule<>(
                contract,
                Objects.requireNonNull(connectionId, "connectionId must not be null"),
                observationRequirement,
                requiredObservationProfile,
                provider
            );
        }

        private boolean matches(Connection<?> connection) {
            return contract.equals(connection.from().contract())
                && (connectionId == null || connectionId.equals(connection.id()));
        }

        private boolean sameSelector(Rule<?> other) {
            return contract.equals(other.contract)
                && Objects.equals(connectionId, other.connectionId);
        }

        private String selectorDescription() {
            if (connectionId != null) {
                return "connection '" + connectionId + "'";
            }
            return "contract '" + contract.id() + "' (" + contract.contractType().getName() + ")";
        }
    }

    /** Internal boundary between routing configuration and runtime connection execution. */
    static final class Selection<C> {
        private final RoutingMode mode;
        private final ObservationRequirement observationRequirement;
        private final RequiredObservationProfile requiredObservationProfile;
        private final ConnectionRouteProvider<C> provider;

        private Selection(
            RoutingMode mode,
            ObservationRequirement observationRequirement,
            RequiredObservationProfile requiredObservationProfile,
            ConnectionRouteProvider<C> provider
        ) {
            this.mode = Objects.requireNonNull(mode, "mode must not be null");
            this.observationRequirement = Objects.requireNonNull(
                observationRequirement,
                "observationRequirement must not be null"
            );
            this.requiredObservationProfile = requiredObservationProfile;
            this.provider = Objects.requireNonNull(provider, "provider must not be null");
            if (this.mode == RoutingMode.DIRECT
                && this.observationRequirement != ObservationRequirement.DISABLED) {
                throw new IllegalArgumentException(
                    "Direct connections cannot require route observation"
                );
            }
        }

        RoutingMode mode() {
            return mode;
        }

        ObservationRequirement observationRequirement() {
            return observationRequirement;
        }

        Optional<RequiredObservationProfile> requiredObservationProfile() {
            return Optional.ofNullable(requiredObservationProfile);
        }

        ConnectionRoute<C> prepare(
            ConnectionDescriptor connection,
            ConnectionObservations observations,
            InteractionDecisionCoordinator coordinator,
            EndpointBinding<C> directTarget
        ) {
            return provider.prepare(new ConnectionRouteContext<>(
                connection,
                observationRequirement,
                requiredObservationProfile,
                observations,
                coordinator,
                directTarget
            ));
        }

        EndpointBinding<C> consumerTarget(ConnectionRoute<C> route) {
            return Objects.requireNonNull(route, "route must not be null").consumerTarget();
        }

        EffectiveObservationStatus observationStatus(ConnectionRoute<C> route) {
            return Objects.requireNonNull(route, "route must not be null").observationStatus();
        }

        boolean semanticControlsDeclared() {
            return mode == RoutingMode.ROUTED
                && observationRequirement == ObservationRequirement.REQUIRED
                && provider instanceof SemanticControlRouteCapability
                && requiredObservationProfile != null
                && requiredObservationProfile.capabilities().contains(
                    Capability.SEMANTIC_CONTROL
                );
        }

        boolean semanticControlsMaterialized(ConnectionRoute<C> route) {
            return semanticControlsDeclared()
                && Objects.requireNonNull(route, "route must not be null")
                    .semanticControlsMaterialized();
        }

        void close(ConnectionRoute<C> route) throws Exception {
            Objects.requireNonNull(route, "route must not be null").close();
        }
    }
}
