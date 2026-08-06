package io.github.jacekkardys.systemproof.examples.sms;

import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
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
import io.github.jacekkardys.systemproof.http.HttpEvidence;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestContentType;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestMethod;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestTarget;
import io.github.jacekkardys.systemproof.http.HttpEvidence.ResponseCompleted;
import io.github.jacekkardys.systemproof.http.HttpExchangeRef;
import io.github.jacekkardys.systemproof.http.HttpProtocolAdapter;
import io.github.jacekkardys.systemproof.http.HttpRequestCorrelation;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Observes and controls the unchanged Jasmin HTTP callback in the reference topology. */
@Tag("docker")
final class HttpCallbackEvidenceIT {
    private static final int REPETITIONS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(
        1024 * 1024,
        2 * 1024 * 1024
    );

    @Test
    void shouldCorrelateHoldAndClassifyTheRealCallbackFiveTimes() throws Exception {
        ObservedSmsEnvironment environment = ObservedSmsEnvironment.define();
        try {
            environment.start();
            assertRequiredObservedRoute(environment);
            verifyAmbiguousCorrelationDoesNotSelectAResponse(environment);

            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                verifyOneCallback(environment);
            }
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldFailRequiredObservationWithoutLeakingAPolicyException() throws Exception {
        String secret = "policy-exception-secret-token";
        ObservedSmsEnvironment environment = ObservedSmsEnvironment.define(interaction -> {
            throw new IllegalStateException("policy failed with " + secret);
        });
        try {
            environment.start();
            environment.smsc().send(TestSms.unique());

            Awaitility.await("HTTP observation failed closed")
                .atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(
                    environment.runtimeConnection(environment.httpConnectionId())
                        .effectiveObservationStatus()
                ).isEqualTo(EffectiveObservationStatus.FAILED));

            assertThat(httpEvidence(environment)).isEmpty();
            assertThat(environment.journalSnapshot().entries().toString())
                .doesNotContain(secret);
            assertThat(environment.diagnostics().content()).doesNotContain(secret);
        } finally {
            environment.close();
        }
    }

    private static void verifyOneCallback(ObservedSmsEnvironment environment)
        throws Exception {
        TestSms target = TestSms.forProof(UUID.randomUUID().toString());
        CorrelationKey targetKey = SmsMessageFingerprint.of(target);
        ProofSubjectRef subject = environment.proofSubjects().create();
        environment.proofSubjects().arm(subject, targetKey);
        SemanticHold hold = positiveResponseHold(environment, subject, targetKey);

        int responsesBeforeUnrelated = positiveResponses(environment).size();
        TestSms unrelated = TestSms.unique();
        environment.smsc().send(unrelated);
        awaitPositiveResponses(environment, responsesBeforeUnrelated + 1);
        assertThat(environment.database().await().rawAndOutboxVisible(unrelated).rawCount())
            .isEqualTo(1);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.ARMED);

        environment.smsc().send(target);
        hold.reached().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(hold.state()).isEqualTo(SemanticHoldState.REACHED_HELD);

        CorrelationResult<HttpExchangeRef> correlation =
            environment.proofSubjects().correlation(
                subject,
                targetKey,
                HttpExchangeRef.codec()
            );
        assertThat(correlation).isInstanceOf(CorrelationResult.Unique.class);
        HttpExchangeRef exchange =
            ((CorrelationResult.Unique<HttpExchangeRef>) correlation).nativeReference();

        List<RequestCompleted> requests = httpEvidence(environment).stream()
            .filter(RequestCompleted.class::isInstance)
            .map(RequestCompleted.class::cast)
            .filter(request -> request.exchange().equals(exchange))
            .toList();
        List<ResponseCompleted> responses = httpEvidence(environment).stream()
            .filter(ResponseCompleted.class::isInstance)
            .map(ResponseCompleted.class::cast)
            .filter(response -> response.exchange().equals(exchange))
            .toList();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(RequestMethod.POST);
            assertThat(request.target())
                .isEqualTo(RequestTarget.ofPath("/v1/ingestion/sms"));
            assertThat(request.contentType())
                .isEqualTo(RequestContentType.FORM_URLENCODED);
            assertThat(request.bodyByteCount()).isPositive();
        });
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.acknowledgement()).isEqualTo(Acknowledgement.POSITIVE);
            assertThat(response.bodyByteCount()).isEqualTo(10);
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
            HttpExchangeRef.codec()
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
        Awaitility.await("positive HTTP callback response evidence")
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
                environment.httpConnectionId(),
                FlowDirection.PROVIDER_TO_CONSUMER,
                environment.adapter().evidenceCodec(),
                evidence -> evidence instanceof ResponseCompleted response
                    && response.acknowledgement() == Acknowledgement.POSITIVE
            ).forSubject(subject).through(
                key,
                HttpExchangeRef.codec(),
                evidence -> ((ResponseCompleted) evidence).exchange()
            ),
            TIMEOUT
        );
    }

    private static List<ResponseCompleted> positiveResponses(
        ObservedSmsEnvironment environment
    ) {
        return httpEvidence(environment).stream()
            .filter(ResponseCompleted.class::isInstance)
            .map(ResponseCompleted.class::cast)
            .filter(response -> response.acknowledgement() == Acknowledgement.POSITIVE)
            .toList();
    }

    private static List<HttpEvidence> httpEvidence(ObservedSmsEnvironment environment) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(InteractionObservationEvent.class::isInstance)
            .map(InteractionObservationEvent.class::cast)
            .filter(event -> event.evidence().schemaId().equals(
                environment.adapter().evidenceCodec().schemaId()
            ))
            .map(event -> event.evidence().decode(
                environment.adapter().evidenceCodec()
            ))
            .toList();
    }

    private static void assertRequiredObservedRoute(ObservedSmsEnvironment environment) {
        assertThat(environment.runtimeConnection(environment.httpConnectionId()))
            .satisfies(connection -> {
                assertThat(connection.state()).isEqualTo(ConnectionState.RUNNING);
                assertThat(connection.routingMode()).isEqualTo(RoutingMode.ROUTED);
                assertThat(connection.observationRequirement())
                    .isEqualTo(ObservationRequirement.REQUIRED);
                assertThat(connection.effectiveObservationStatus())
                    .isEqualTo(EffectiveObservationStatus.ACTIVE);
            });
    }

    private static InetSocketAddress address(URI endpoint) {
        int port = endpoint.getPort() >= 0 ? endpoint.getPort() : 80;
        return new InetSocketAddress(endpoint.getHost(), port);
    }

    private static URI replaceAddress(URI endpoint, String host, int port) {
        try {
            return new URI(
                endpoint.getScheme(),
                endpoint.getUserInfo(),
                host,
                port,
                endpoint.getPath(),
                endpoint.getQuery(),
                endpoint.getFragment()
            );
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Cannot replace HTTP endpoint address", failure);
        }
    }

    private static final class ObservedSmsEnvironment extends Environment {
        private final SmscComponent smsc;
        private final JasminComponent jasmin;
        private final PostgresComponent database;
        private final HttpProtocolAdapter adapter;

        private ObservedSmsEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing,
            SmscComponent smsc,
            JasminComponent jasmin,
            PostgresComponent database,
            HttpProtocolAdapter adapter
        ) {
            super(topology, logging, routing);
            this.smsc = smsc;
            this.jasmin = jasmin;
            this.database = database;
            this.adapter = adapter;
        }

        private static ObservedSmsEnvironment define() {
            return define(SmsMessageFingerprint.httpCallbackCorrelation());
        }

        private static ObservedSmsEnvironment define(
            HttpRequestCorrelation requestCorrelation
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

            HttpProtocolAdapter adapter = new HttpProtocolAdapter(
                requestCorrelation
            );
            InteractionGateway gateway = new InteractionGateway();
            ConnectionRouting routing = ConnectionRouting.routed(
                jasmin.sms().contract(),
                new RequiredObservationProfile(
                    adapter.evidenceCodec().schemaId(),
                    Optional.of(HttpExchangeRef.codec().schemaId()),
                    Set.of(
                        Capability.CORRELATION_CONTRIBUTIONS,
                        Capability.SEMANTIC_CONTROL
                    ),
                    Set.of()
                ),
                gateway.tcp(
                    endpoint(
                        HttpCallbackEvidenceIT::address,
                        HttpCallbackEvidenceIT::replaceAddress
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
                jasmin,
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

        private ConnectionId httpConnectionId() {
            return connectionFrom(jasmin.sms()).id();
        }

        private HttpProtocolAdapter adapter() {
            return adapter;
        }
    }
}
