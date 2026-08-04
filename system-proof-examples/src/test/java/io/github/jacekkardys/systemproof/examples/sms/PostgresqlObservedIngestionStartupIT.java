package io.github.jacekkardys.systemproof.examples.sms;

import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.environment.ConnectionRouting;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion.SmsIngestionComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresComponent;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Prerequisite;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;

/** Verifies that the complete reference ingestion service starts through PostgreSQL observation. */
@Tag("docker")
final class PostgresqlObservedIngestionStartupIT {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(
        1024 * 1024,
        2 * 1024 * 1024
    );

    @Test
    void shouldRunFlywayAndReadinessTrafficThroughRequiredObservation() {
        ObservedStartupEnvironment environment = ObservedStartupEnvironment.define();
        try {
            environment.start();

            assertThat(environment.runtimeConnections())
                .singleElement()
                .satisfies(connection -> {
                    assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                    assertThat(connection.routingMode()).isEqualTo(RoutingMode.ROUTED);
                    assertThat(connection.observationRequirement())
                        .isEqualTo(ObservationRequirement.REQUIRED);
                    assertThat(connection.effectiveObservationStatus())
                        .isEqualTo(EffectiveObservationStatus.ACTIVE);
                });
        } finally {
            environment.close();
        }
    }

    private static InetSocketAddress address(JdbcEndpoint endpoint) {
        URI uri = URI.create(endpoint.url().substring("jdbc:".length()));
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    private static JdbcEndpoint replaceAddress(
        JdbcEndpoint endpoint,
        String host,
        int port
    ) {
        URI uri = URI.create(endpoint.url().substring("jdbc:".length()));
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new JdbcEndpoint(
            "jdbc:postgresql://" + host + ":" + port + uri.getRawPath() + query,
            endpoint.username(),
            endpoint.password()
        );
    }

    private static final class ObservedStartupEnvironment extends Environment {
        private ObservedStartupEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing
        ) {
            super(topology, logging, routing);
        }

        private static ObservedStartupEnvironment define() {
            EnvironmentBuilder builder = new EnvironmentBuilder();
            SmsIngestionComponent ingestion = builder.component(SmsIngestionComponent.class);
            PostgresComponent database = builder.component(PostgresComponent.class);
            builder.connect(ingestion.jdbc(), database.jdbc());

            InteractionGateway gateway = new InteractionGateway();
            PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter();
            ConnectionRouting routing = ConnectionRouting.routed(
                ingestion.jdbc().contract(),
                new RequiredObservationProfile(
                    adapter.evidenceCodec().schemaId(),
                    Optional.empty(),
                    Set.of(
                        Capability.SEMANTIC_CONTROL,
                        Capability.DURABLE_SUCCESS
                    ),
                    Set.of(Prerequisite.EXACT_SESSION_DURABILITY),
                    Set.of()
                ),
                gateway.tcp(
                    endpoint(
                        PostgresqlObservedIngestionStartupIT::address,
                        PostgresqlObservedIngestionStartupIT::replaceAddress
                    ),
                    adapter,
                    LIMITS
                )
            );
            return builder.build((topology, logging) ->
                new ObservedStartupEnvironment(topology, logging, routing));
        }
    }
}
