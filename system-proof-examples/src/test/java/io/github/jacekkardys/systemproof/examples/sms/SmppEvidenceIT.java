package io.github.jacekkardys.systemproof.examples.sms;

import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldSelector;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.endpoint.SmppEndpoint;
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
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.smpp.SmppDeliverCorrelation;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DataCoding;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppExchangeRef;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Observes and controls the unchanged SMPP session in the reference SMS topology. */
@Tag("docker")
final class SmppEvidenceIT {
    private static final int REPETITIONS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ProtocolLimits SMPP_LIMITS = new ProtocolLimits(
        64 * 1024,
        128 * 1024
    );

    @Test
    void shouldCorrelateHoldAndClassifyTheRealSmppExchangeFiveTimes()
        throws Exception {
        ObservedSmsEnvironment environment = ObservedSmsEnvironment.define();
        try {
            environment.start();
            assertRequiredObservedRoutes(environment);
            verifyAmbiguousCorrelationDoesNotSelectAResponse(environment);

            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                verifyOneExchange(environment);
            }
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldFailRequiredObservationWithoutLeakingAPolicyException() {
        String secret = "smpp-policy-exception-secret-token";
        ObservedSmsEnvironment environment = ObservedSmsEnvironment.define(interaction -> {
            throw new IllegalStateException("policy failed with " + secret);
        });
        try {
            environment.start();
            TestSms rejected = TestSms.unique();
            environment.smsc().send(rejected);

            Awaitility.await("SMPP observation failed closed")
                .atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(
                    environment.runtimeConnection(environment.smppConnectionId())
                        .effectiveObservationStatus()
                ).isEqualTo(EffectiveObservationStatus.FAILED));

            assertThat(smppEvidence(environment))
                .noneMatch(DeliverSmCompleted.class::isInstance)
                .noneMatch(DeliverSmResponseCompleted.class::isInstance);
            Awaitility.await("failed REQUIRED observation does not forward the SMS")
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(environment.database().snapshot(rejected))
                    .satisfies(persistence -> {
                        assertThat(persistence.rawCount()).isZero();
                        assertThat(persistence.outboxCount()).isZero();
                    }));
            assertThat(environment.journalSnapshot().entries().toString())
                .doesNotContain(secret);
            assertThat(environment.diagnostics().content()).doesNotContain(secret);
        } finally {
            environment.close();
        }
    }

    private static void verifyOneExchange(ObservedSmsEnvironment environment)
        throws Exception {
        TestSms target = TestSms.forProof(UUID.randomUUID().toString());
        CorrelationKey targetKey = SmsMessageFingerprint.of(target);
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, targetKey);
        SemanticHold hold = positiveResponseHold(environment, subject, targetKey);

        int responsesBeforeUnrelated = positiveResponses(environment).size();
        TestSms unrelated = TestSms.unique();
        assertThat(SmsMessageFingerprint.of(unrelated)).isNotEqualTo(targetKey);
        environment.smsc().send(unrelated);
        awaitPositiveResponses(environment, responsesBeforeUnrelated + 1);
        assertThat(environment.database().await().rawAndOutboxVisible(unrelated).rawCount())
            .isEqualTo(1);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.ARMED);

        environment.smsc().send(target);
        hold.reached().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(hold.state())
            .as("the response is recorded but no byte has been forwarded")
            .isEqualTo(SemanticHoldState.REACHED_HELD);

        SmppExchangeRef exchange = uniqueCorrelation(
            environment,
            subject,
            targetKey,
            SmppExchangeRef.codec()
        );
        List<ObservedSmppEvidence> exchangeEvidence = observedSmppEvidence(environment)
            .stream()
            .filter(observed -> exchange.equals(exchangeOf(observed.evidence())))
            .toList();
        assertThat(exchangeEvidence)
            .filteredOn(observed -> observed.evidence() instanceof DeliverSmCompleted)
            .singleElement()
            .satisfies(observed -> {
                DeliverSmCompleted deliver = (DeliverSmCompleted) observed.evidence();
                assertThat(observed.direction())
                    .isEqualTo(FlowDirection.PROVIDER_TO_CONSUMER);
                assertThat(deliver.exchange()).isEqualTo(exchange);
                assertThat(deliver.wireSequenceNumber())
                    .isEqualTo(exchange.wireSequenceNumber());
                assertThat(deliver.dataCoding()).isEqualTo(DataCoding.UCS2);
                assertThat(deliver.esmClass()).isZero();
                assertThat(deliver.messageByteCount()).isPositive();
                assertThat(deliver.bodyByteCount())
                    .isEqualTo(deliver.pduByteCount() - 16);
            });
        assertThat(exchangeEvidence)
            .filteredOn(observed ->
                observed.evidence() instanceof DeliverSmResponseCompleted)
            .singleElement()
            .satisfies(observed -> {
                DeliverSmResponseCompleted response =
                    (DeliverSmResponseCompleted) observed.evidence();
                assertThat(observed.direction())
                    .isEqualTo(FlowDirection.CONSUMER_TO_PROVIDER);
                assertThat(response.exchange()).isEqualTo(exchange);
                assertThat(response.wireSequenceNumber())
                    .isEqualTo(exchange.wireSequenceNumber());
                assertThat(response.commandStatus()).isZero();
                assertThat(response.acknowledgement())
                    .isEqualTo(Acknowledgement.POSITIVE);
                assertThat(response.pduByteCount()).isEqualTo(17);
            });

        hold.release().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.FORWARDED);
        SmsPersistence persisted = environment.database().await()
            .rawAndOutboxVisible(target);
        assertThat(persisted.rawCount()).isEqualTo(1);
        assertThat(persisted.outboxCount()).isEqualTo(1);
        assertThat(persisted.sourceAddress()).isEqualTo(target.sourceAddress());
        assertThat(persisted.destinationAddress()).isEqualTo(target.destinationAddress());
        assertThat(persisted.content()).isEqualTo(target.content());

    }

    private static void verifyAmbiguousCorrelationDoesNotSelectAResponse(
        ObservedSmsEnvironment environment
    ) {
        TestSms repeated = TestSms.forProof(UUID.randomUUID().toString());
        CorrelationKey key = SmsMessageFingerprint.of(repeated);
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, key);

        sendAndAwaitPositiveResponse(environment, repeated);
        sendAndAwaitPositiveResponse(environment, repeated);
        assertThat(environment.proofSubjects().correlation(
            subject,
            key,
            SmppExchangeRef.codec()
        )).isInstanceOf(CorrelationResult.Ambiguous.class);

        SemanticHold hold = positiveResponseHold(environment, subject, key);
        sendAndAwaitPositiveResponse(environment, repeated);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.ARMED);
        assertThat(hold.cancel()).isTrue();
        assertThat(hold.state()).isEqualTo(SemanticHoldState.CANCELLED);
    }

    private static void sendAndAwaitPositiveResponse(
        ObservedSmsEnvironment environment,
        TestSms message
    ) {
        int before = positiveResponses(environment).size();
        environment.smsc().send(message);
        awaitPositiveResponses(environment, before + 1);
    }

    private static void awaitPositiveResponses(
        ObservedSmsEnvironment environment,
        int expected
    ) {
        Awaitility.await("positive deliver_sm_resp evidence")
            .atMost(TIMEOUT)
            .untilAsserted(() -> assertThat(positiveResponses(environment))
                .hasSizeGreaterThanOrEqualTo(expected));
    }

    private static SemanticHold positiveResponseHold(
        ObservedSmsEnvironment environment,
        ProofSubjectRef subject,
        CorrelationKey key
    ) {
        return environment.controls().arm(
            SemanticHoldSelector.matching(
                environment.smppConnectionId(),
                FlowDirection.CONSUMER_TO_PROVIDER,
                environment.smppAdapter().evidenceCodec(),
                evidence -> evidence instanceof DeliverSmResponseCompleted response
                    && response.acknowledgement() == Acknowledgement.POSITIVE
            ).forSubject(subject).through(
                key,
                SmppExchangeRef.codec(),
                evidence -> ((DeliverSmResponseCompleted) evidence).exchange()
            ),
            TIMEOUT
        );
    }

    private static List<DeliverSmResponseCompleted> positiveResponses(
        ObservedSmsEnvironment environment
    ) {
        return smppEvidence(environment).stream()
            .filter(DeliverSmResponseCompleted.class::isInstance)
            .map(DeliverSmResponseCompleted.class::cast)
            .filter(response -> response.acknowledgement() == Acknowledgement.POSITIVE)
            .toList();
    }

    private static List<SmppEvidence> smppEvidence(ObservedSmsEnvironment environment) {
        return observedSmppEvidence(environment).stream()
            .map(ObservedSmppEvidence::evidence)
            .toList();
    }

    private static List<ObservedSmppEvidence> observedSmppEvidence(
        ObservedSmsEnvironment environment
    ) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(InteractionObservationEvent.class::isInstance)
            .map(InteractionObservationEvent.class::cast)
            .filter(event -> event.evidence().schemaId().equals(
                environment.smppAdapter().evidenceCodec().schemaId()
            ))
            .map(event -> new ObservedSmppEvidence(
                event.interactionRef().direction(),
                event.evidence().decode(environment.smppAdapter().evidenceCodec())
            ))
            .toList();
    }

    private static SmppExchangeRef exchangeOf(SmppEvidence evidence) {
        if (evidence instanceof DeliverSmCompleted deliver) {
            return deliver.exchange();
        }
        if (evidence instanceof DeliverSmResponseCompleted response) {
            return response.exchange();
        }
        return null;
    }

    private static <T> T uniqueCorrelation(
        ObservedSmsEnvironment environment,
        ProofSubjectRef subject,
        CorrelationKey key,
        io.github.jacekkardys.systemproof.observation.EvidenceCodec<T> codec
    ) {
        CorrelationResult<T> correlation = environment.proofSubjects().correlation(
            subject,
            key,
            codec
        );
        assertThat(correlation).isInstanceOf(CorrelationResult.Unique.class);
        return ((CorrelationResult.Unique<T>) correlation).nativeReference();
    }

    private static void assertRequiredObservedRoutes(
        ObservedSmsEnvironment environment
    ) {
        assertRequiredObservedRoute(environment, environment.smppConnectionId());
    }

    private static void assertRequiredObservedRoute(
        ObservedSmsEnvironment environment,
        ConnectionId connectionId
    ) {
        assertThat(environment.runtimeConnection(connectionId))
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.routingMode()).isEqualTo(RoutingMode.ROUTED);
                assertThat(connection.observationRequirement())
                    .isEqualTo(ObservationRequirement.REQUIRED);
                assertThat(connection.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE);
            });
    }

    private static RequiredObservationProfile requiredProfile(
        io.github.jacekkardys.systemproof.observation.EvidenceCodec<?> evidenceCodec,
        io.github.jacekkardys.systemproof.observation.EvidenceCodec<?> referenceCodec
    ) {
        return new RequiredObservationProfile(
            evidenceCodec.schemaId(),
            Optional.of(referenceCodec.schemaId()),
            Set.of(
                Capability.CORRELATION_CONTRIBUTIONS,
                Capability.SEMANTIC_CONTROL
            ),
            Set.of()
        );
    }

    private static InetSocketAddress smppAddress(SmppEndpoint endpoint) {
        return new InetSocketAddress(endpoint.host(), endpoint.port());
    }

    private static SmppEndpoint replaceSmppAddress(
        SmppEndpoint endpoint,
        String host,
        int port
    ) {
        return new SmppEndpoint(host, port, endpoint.systemId(), endpoint.password());
    }

    private record ObservedSmppEvidence(
        FlowDirection direction,
        SmppEvidence evidence
    ) {}

    private static final class ObservedSmsEnvironment extends Environment {
        private final SmscComponent smsc;
        private final JasminComponent jasmin;
        private final PostgresComponent database;
        private final SmppProtocolAdapter smppAdapter;

        private ObservedSmsEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing,
            SmscComponent smsc,
            JasminComponent jasmin,
            PostgresComponent database,
            SmppProtocolAdapter smppAdapter
        ) {
            super(topology, logging, routing);
            this.smsc = smsc;
            this.jasmin = jasmin;
            this.database = database;
            this.smppAdapter = smppAdapter;
        }

        private static ObservedSmsEnvironment define() {
            return define(SmsMessageFingerprint.smppDeliverCorrelation());
        }

        private static ObservedSmsEnvironment define(
            SmppDeliverCorrelation deliverCorrelation
        ) {
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

            SmppProtocolAdapter smppAdapter = new SmppProtocolAdapter(deliverCorrelation);
            InteractionGateway gateway = new InteractionGateway();
            ConnectionRouting routing = ConnectionRouting.routed(
                jasmin.smpp().contract(),
                requiredProfile(smppAdapter.evidenceCodec(), SmppExchangeRef.codec()),
                gateway.tcp(
                    endpoint(
                        SmppEvidenceIT::smppAddress,
                        SmppEvidenceIT::replaceSmppAddress
                    ),
                    smppAdapter,
                    SMPP_LIMITS
                )
            );
            return builder.build((topology, logging) -> new ObservedSmsEnvironment(
                topology,
                logging,
                routing,
                smsc,
                jasmin,
                database,
                smppAdapter
            ));
        }

        private UkarimSmscOperations smsc() {
            return operations(smsc);
        }

        private SmsDatabaseOperations database() {
            return operations(database);
        }

        private ConnectionId smppConnectionId() {
            return connectionFrom(jasmin.smpp()).id();
        }

        private SmppProtocolAdapter smppAdapter() {
            return smppAdapter;
        }
    }
}
