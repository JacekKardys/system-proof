package io.github.jacekkardys.systemproof.examples.sms;

import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldSelector;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.environment.ConnectionRouting;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion.SmsIngestionComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin.JasminComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.SmsDatabaseOperations;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.redis.RedisComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.SmscComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.UkarimSmscOperations;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsMessageFingerprint;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitAttempt;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitSucceeded;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlProtocolAdapter;
import io.github.jacekkardys.systemproof.postgresql.TransactionRef;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Correlates and controls the unchanged containerized reference ingestion transaction. */
@Tag("docker")
final class PostgresqlObservedIngestionStartupIT {
    private static final int REPETITIONS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(
        1024 * 1024,
        2 * 1024 * 1024
    );
    private static final PostgresqlDurabilityRequirements DURABILITY_REQUIREMENTS =
        new PostgresqlDurabilityRequirements(Set.of(
            new Table("public", "raw_sms_event"),
            new Table("public", "outbox_event")
        ));

    @Test
    void shouldCorrelateHoldAndConfirmTheRealIngestionCommitFiveTimes()
        throws Exception {
        ObservedSmsEnvironment environment = ObservedSmsEnvironment.define();
        ExecutorService submissions = Executors.newSingleThreadExecutor();
        try {
            environment.start();

            assertRequiredObservedRoute(environment);
            assertThat(environment.database()
                .durabilityPreflight(DURABILITY_REQUIREMENTS)
                .requireSatisfied()).isNotNull();
            assertThat(commitSuccesses(environment, environment.adapter()))
                .as("Flyway must complete an unrelated observed transaction before proof traffic")
                .isNotEmpty();

            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                verifyOneMessage(environment, submissions);
            }
        } finally {
            environment.close();
            submissions.shutdownNow();
            assertThat(submissions.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void verifyOneMessage(
        ObservedSmsEnvironment environment,
        ExecutorService submissions
    ) throws Exception {
        String proofDiscriminator = UUID.randomUUID().toString();
        TestSms message = TestSms.forProof(proofDiscriminator);
        CorrelationKey messageKey = SmsMessageFingerprint.of(message);
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, messageKey);

        SmsPersistence before = environment.database().snapshot(message);
        assertThat(before.rawCount()).isZero();
        assertThat(before.outboxCount()).isZero();

        SemanticHold hold = environment.controls().arm(
            SemanticHoldSelector.matching(
                environment.databaseConnectionId(),
                FlowDirection.CONSUMER_TO_PROVIDER,
                environment.adapter().evidenceCodec(),
                CommitAttempt.class::isInstance
            ).forSubject(subject).through(
                messageKey,
                TransactionRef.codec(),
                evidence -> ((CommitAttempt) evidence).transaction()
            ),
            TIMEOUT
        );

        Future<?> submission = submissions.submit(() -> environment.smsc().send(message));
        hold.reached().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        CorrelationResult<TransactionRef> correlation = environment.proofSubjects().correlation(
            subject,
            messageKey,
            TransactionRef.codec()
        );
        assertThat(correlation).isInstanceOf(CorrelationResult.Unique.class);
        TransactionRef transaction = ((CorrelationResult.Unique<TransactionRef>) correlation)
            .nativeReference();

        SmsPersistence held = environment.database().snapshot(message);
        assertThat(held.rawCount()).isZero();
        assertThat(held.outboxCount()).isZero();
        assertThat(commitSuccesses(environment, environment.adapter()))
            .noneMatch(success -> success.transaction().equals(transaction));

        hold.release().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        Awaitility.await("matching PostgreSQL commit confirmation")
            .atMost(TIMEOUT)
            .untilAsserted(() -> assertThat(
                commitSuccesses(environment, environment.adapter())
            ).anyMatch(success -> success.transaction().equals(transaction)));

        SmsPersistence persisted = environment.database().await()
            .rawAndOutboxVisible(message);
        assertThat(persisted.rawCount()).isEqualTo(1);
        assertThat(persisted.outboxCount()).isEqualTo(1);
        assertThat(persisted.rawId()).isEqualTo(persisted.outboxAggregateId());
        assertThat(persisted.sourceAddress()).isEqualTo(message.sourceAddress());
        assertThat(persisted.destinationAddress()).isEqualTo(message.destinationAddress());
        assertThat(persisted.content()).isEqualTo(message.content());

        submission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private static List<CommitSucceeded> commitSuccesses(
        Environment environment,
        PostgresqlProtocolAdapter adapter
    ) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(InteractionObservationEvent.class::isInstance)
            .map(InteractionObservationEvent.class::cast)
            .filter(event -> event.evidence().schemaId().equals(
                adapter.evidenceCodec().schemaId()
            ))
            .map(event -> event.evidence().decode(adapter.evidenceCodec()))
            .filter(CommitSucceeded.class::isInstance)
            .map(CommitSucceeded.class::cast)
            .toList();
    }

    private static void assertRequiredObservedRoute(ObservedSmsEnvironment environment) {
        assertThat(environment.runtimeConnection(environment.databaseConnectionId()))
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.routingMode()).isEqualTo(RoutingMode.ROUTED);
                assertThat(connection.observationRequirement())
                    .isEqualTo(ObservationRequirement.REQUIRED);
                assertThat(connection.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE);
            });
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

    private static final class ObservedSmsEnvironment extends Environment {
        private final SmscComponent smsc;
        private final SmsIngestionComponent ingestion;
        private final PostgresComponent database;
        private final PostgresqlProtocolAdapter adapter;

        private ObservedSmsEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing,
            SmscComponent smsc,
            SmsIngestionComponent ingestion,
            PostgresComponent database,
            PostgresqlProtocolAdapter adapter
        ) {
            super(topology, logging, routing);
            this.smsc = smsc;
            this.ingestion = ingestion;
            this.database = database;
            this.adapter = adapter;
        }

        private static ObservedSmsEnvironment define() {
            EnvironmentBuilder builder = new EnvironmentBuilder();
            SmscComponent smsc = builder.component(SmscComponent.class);
            JasminComponent jasmin = builder.component(JasminComponent.class);
            SmsIngestionComponent ingestion = builder.component(SmsIngestionComponent.class);
            PostgresComponent database = builder.component(PostgresComponent.class);
            RabbitMqComponent broker = builder.component(RabbitMqComponent.class);
            RedisComponent state = builder.component(RedisComponent.class);
            builder
                .connect(jasmin.smpp(), smsc.smpp())
                .connect(jasmin.sms(), ingestion.sms())
                .connect(ingestion.jdbc(), database.jdbc())
                .connect(jasmin.amqp(), broker.amqp())
                .connect(jasmin.redis(), state.redis());

            PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter(
                SmsMessageFingerprint.rawWriteCorrelation()
            );
            InteractionGateway gateway = new InteractionGateway();
            ConnectionRouting routing = ConnectionRouting.routed(
                ingestion.jdbc().contract(),
                new RequiredObservationProfile(
                    adapter.evidenceCodec().schemaId(),
                    Optional.of(TransactionRef.codec().schemaId()),
                    Set.of(
                        Capability.CORRELATION_CONTRIBUTIONS,
                        Capability.SEMANTIC_CONTROL
                    ),
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
            return builder.build((topology, logging) -> new ObservedSmsEnvironment(
                topology,
                logging,
                routing,
                smsc,
                ingestion,
                database,
                adapter
            ));
        }

        private UkarimSmscOperations smsc() {
            return operations(smsc);
        }

        private SmsDatabaseOperations database() {
            return operations(database);
        }

        private ConnectionId databaseConnectionId() {
            return connectionFrom(ingestion.jdbc()).id();
        }

        private PostgresqlProtocolAdapter adapter() {
            return adapter;
        }
    }
}
