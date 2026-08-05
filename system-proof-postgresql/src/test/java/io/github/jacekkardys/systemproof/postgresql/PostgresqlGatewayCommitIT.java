package io.github.jacekkardys.systemproof.postgresql;

import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;
import static io.github.jacekkardys.systemproof.testcontainers.gateway.TcpEndpointAdapter.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import io.github.jacekkardys.systemproof.communication.Communication;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.SystemComponent;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.DriverConfig;
import io.github.jacekkardys.systemproof.configuration.Secret;
import io.github.jacekkardys.systemproof.control.SemanticHold;
import io.github.jacekkardys.systemproof.control.SemanticHoldSelector;
import io.github.jacekkardys.systemproof.control.SemanticHoldState;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.environment.ConnectionRouting;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentLogging;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitAttempt;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitSucceeded;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.testcontainers.gateway.InteractionGateway;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;
import io.github.jacekkardys.systemproof.topology.PortContract;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import io.github.jacekkardys.systemproof.topology.StartupPrerequisite;

class PostgresqlGatewayCommitIT {
    private static final int REPETITIONS = 10;
    private static final int COLLISION_REPETITIONS = 10;
    private static final ProtocolLimits LIMITS = new ProtocolLimits(
        1024 * 1024,
        2 * 1024 * 1024
    );
    private static final CorrelationKeySchema KEY_SCHEMA = new CorrelationKeySchema(
        "system-proof.test",
        "postgres-marker",
        1
    );

    @Test
    void shouldHoldExactCorrelatedCommitBeforeAnyReceiverByteTenTimes()
        throws Exception {
        PostgresqlProtocolAdapter protocol = new PostgresqlProtocolAdapter(interaction -> {
            if (!interaction.shape().table().equals("proof_entry")) {
                return Optional.empty();
            }
            int markerIndex = interaction.shape().columns().indexOf("marker");
            if (markerIndex < 0 || interaction.parameterIsNull(markerIndex)) {
                return Optional.empty();
            }
            return Optional.of(key(interaction.parameterBytes(markerIndex)));
        });
        FailureCapturingAdapter adapter = new FailureCapturingAdapter(protocol);
        GatewayEnvironment environment = GatewayEnvironment.define(adapter);
        ExecutorService commits = Executors.newSingleThreadExecutor();
        try {
            environment.start();
            DatabaseOperations database = environment.database();
            ClientOperations client = environment.client();
            try (Connection sut = client.connect();
                 Connection verification = database.connectDirect();
                 Statement setup = verification.createStatement()) {
                setup.execute("""
                    CREATE TABLE proof_entry (
                        id bigint PRIMARY KEY,
                        marker varchar(128) NOT NULL
                    )
                    """);

                PostgresqlDurabilityRequirements requirements =
                    new PostgresqlDurabilityRequirements(Set.of(
                        new Table("public", "proof_entry")
                ));
                PostgresqlDurabilityResult durability = PostgresqlDurabilityVerifier.verify(
                    verification,
                    requirements
                );
                assertThat(durability.synchronousCommit())
                    .isEqualTo(PostgresqlDurabilityResult.Setting.ON);
                assertThat(durability.fsync())
                    .isEqualTo(PostgresqlDurabilityResult.Setting.ON);
                assertThat(durability.relations())
                    .containsEntry(
                        new Table("public", "proof_entry"),
                        PostgresqlDurabilityResult.RelationStatus.PERMANENT_TABLE
                    );
                assertThat(durability.requireSatisfied()).isSameAs(durability);

                ConnectionId connectionId = environment.connectionFrom(
                    environment.clientComponent().jdbc()
                ).id();

                sut.setAutoCommit(false);
                try (PreparedStatement unrelated = sut.prepareStatement(
                    "INSERT INTO proof_entry (id, marker) VALUES (?, ?)"
                )) {
                    unrelated.setLong(1, 0);
                    unrelated.setString(2, "unrelated-before-hold");
                    assertThat(unrelated.executeUpdate()).isEqualTo(1);
                }
                sut.commit();
                adapter.awaitCommitDigest();
                assertThat(database.probe().awaitCommitAttempt()).isEqualTo(1);
                assertThat(rowCount(verification, 0)).isEqualTo(1);

                TransactionRef previous = null;
                for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                    String marker = "marker-" + repetition;
                    CorrelationKey key = key(ByteBuffer.wrap(
                        marker.getBytes(StandardCharsets.UTF_8)
                    ));
                    ProofSubjectRef subject = environment.proofSubjects().create();
                    environment.proofSubjects().arm(subject, key);

                    SemanticHold hold = environment.controls().arm(
                        SemanticHoldSelector.matching(
                            connectionId,
                            FlowDirection.CONSUMER_TO_PROVIDER,
                            adapter.evidenceCodec(),
                            CommitAttempt.class::isInstance
                        ).forSubject(subject).through(
                            key,
                            TransactionRef.codec(),
                            evidence -> ((CommitAttempt) evidence).transaction()
                        ),
                        Duration.ofSeconds(10)
                    );

                    try (PreparedStatement write = sut.prepareStatement(
                        "INSERT INTO proof_entry (id, marker) VALUES (?, ?)"
                    )) {
                        write.setLong(1, repetition);
                        write.setString(2, marker);
                        try {
                            assertThat(write.executeUpdate()).isEqualTo(1);
                        } catch (Exception failure) {
                            adapter.requireHealthy();
                            database.probe().requireHealthy();
                            throw failure;
                        }
                    }
                    int receiverAttemptsBefore = database.probe().commitAttempts();
                    Future<?> commit = commits.submit(() -> {
                        try {
                            sut.commit();
                        } catch (Exception failure) {
                            throw new IllegalStateException("JDBC commit failed", failure);
                        }
                    });

                    hold.reached().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    CommitDigest commitDigest = adapter.awaitCommitDigest();
                    TransactionRef transaction = commitDigest.transaction();
                    byte[] gatewayCommitDigest = commitDigest.digest();
                    assertThat(database.probe().commitAttempts())
                        .isEqualTo(receiverAttemptsBefore);
                    assertThat(database.probe().hasPendingCommitNotification()).isFalse();
                    assertThat(rowCount(verification, repetition)).isZero();

                    hold.release().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    assertThat(database.probe().awaitCommitAttempt())
                        .isEqualTo(receiverAttemptsBefore + 1);
                    commit.get(10, TimeUnit.SECONDS);

                    assertThat(database.probe().commitAttempts())
                        .isEqualTo(receiverAttemptsBefore + 1);
                    assertThat(database.probe().lastCommitByteCount()).isPositive();
                    assertThat(database.probe().lastCommitDigest())
                        .containsExactly(gatewayCommitDigest);
                    assertThat(rowCount(verification, repetition)).isEqualTo(1);
                    assertThat(commitSuccessCount(environment, adapter, transaction))
                        .isEqualTo(1);

                    CorrelationResult<TransactionRef> correlation =
                        environment.proofSubjects().correlation(
                            subject,
                            key,
                            TransactionRef.codec()
                        );
                    assertThat(correlation).isInstanceOf(CorrelationResult.Unique.class);
                    assertThat(((CorrelationResult.Unique<TransactionRef>) correlation)
                        .nativeReference()).isEqualTo(transaction);
                    if (previous != null) {
                        assertThat(transaction.sessionOrdinal())
                            .isEqualTo(previous.sessionOrdinal());
                        assertThat(transaction.transactionOrdinal())
                            .isEqualTo(previous.transactionOrdinal() + 1);
                    }
                    previous = transaction;
                }
                assertThat(database.probe().commitAttempts()).isEqualTo(REPETITIONS + 1);
            }
        } finally {
            commits.shutdownNow();
            assertThat(commits.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            environment.close();
        }
    }

    @Test
    void shouldNotJoinCollidingTransactionRefsFromSeparateAdaptersTenTimes()
        throws Exception {
        PostgresqlProtocolAdapter firstProtocol = new PostgresqlProtocolAdapter(
            interaction -> {
                if (!interaction.shape().table().equals("proof_entry")) {
                    return Optional.empty();
                }
                int markerIndex = interaction.shape().columns().indexOf("marker");
                if (markerIndex < 0 || interaction.parameterIsNull(markerIndex)) {
                    return Optional.empty();
                }
                return Optional.of(key(interaction.parameterBytes(markerIndex)));
            }
        );
        PostgresqlProtocolAdapter secondProtocol = new PostgresqlProtocolAdapter(
            ignored -> Optional.empty()
        );
        FailureCapturingAdapter firstAdapter = new FailureCapturingAdapter(firstProtocol);
        FailureCapturingAdapter secondAdapter = new FailureCapturingAdapter(secondProtocol);
        GatewayEnvironment environment = GatewayEnvironment.define(
            firstAdapter,
            secondAdapter
        );
        ExecutorService commits = Executors.newSingleThreadExecutor();
        try {
            environment.start();
            try (Connection verification = environment.database().connectDirect();
                 Statement setup = verification.createStatement()) {
                setup.execute("""
                    CREATE TABLE proof_entry (
                        id bigint PRIMARY KEY,
                        marker varchar(128) NOT NULL
                    )
                    """);
                ConnectionId secondConnectionId = environment.connectionFrom(
                    environment.clientComponent().secondaryJdbc()
                ).id();

                for (int repetition = 1;
                     repetition <= COLLISION_REPETITIONS;
                     repetition++) {
                    String marker = "cross-route-collision-" + repetition;
                    CorrelationKey correlationKey = key(ByteBuffer.wrap(
                        marker.getBytes(StandardCharsets.UTF_8)
                    ));
                    ProofSubjectRef subject = environment.proofSubjects().create();
                    environment.proofSubjects().arm(subject, correlationKey);

                    try (Connection first = environment.client().connect();
                         Connection second = environment.client().connectSecondary()) {
                        first.setAutoCommit(false);
                        try (PreparedStatement write = first.prepareStatement(
                            "INSERT INTO proof_entry (id, marker) VALUES (?, ?)"
                        )) {
                            write.setLong(1, 10_000L + repetition);
                            write.setString(2, marker);
                            assertThat(write.executeUpdate()).isEqualTo(1);
                        }
                        CorrelationResult<TransactionRef> correlation =
                            environment.proofSubjects().correlation(
                                subject,
                                correlationKey,
                                TransactionRef.codec()
                            );
                        assertThat(correlation)
                            .isInstanceOf(CorrelationResult.Unique.class);
                        TransactionRef firstTransaction =
                            ((CorrelationResult.Unique<TransactionRef>) correlation)
                                .nativeReference();
                        if (repetition == 1) {
                            assertThat(firstTransaction)
                                .isEqualTo(new TransactionRef(1, 1));
                        }

                        SemanticHold wrongRouteHold = environment.controls().arm(
                            SemanticHoldSelector.matching(
                                secondConnectionId,
                                FlowDirection.CONSUMER_TO_PROVIDER,
                                secondAdapter.evidenceCodec(),
                                CommitAttempt.class::isInstance
                            ).forSubject(subject).through(
                                correlationKey,
                                TransactionRef.codec(),
                                evidence -> ((CommitAttempt) evidence).transaction()
                            ),
                            Duration.ofSeconds(10)
                        );

                        second.setAutoCommit(false);
                        try (Statement transactionStart = second.createStatement();
                             ResultSet result = transactionStart.executeQuery("SELECT 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getInt(1)).isEqualTo(1);
                        }
                        Future<?> commit = commits.submit(() -> {
                            try {
                                second.commit();
                            } catch (Exception failure) {
                                throw new IllegalStateException("JDBC commit failed", failure);
                            }
                        });
                        commit.get(10, TimeUnit.SECONDS);
                        TransactionRef secondTransaction = secondAdapter
                            .awaitCommitDigest()
                            .transaction();

                        assertThat(secondTransaction).isEqualTo(firstTransaction);
                        assertThat(wrongRouteHold.state())
                            .isEqualTo(SemanticHoldState.ARMED);
                        assertThat(wrongRouteHold.cancel()).isTrue();
                        assertThat(commitSuccessCount(
                            environment,
                            secondAdapter,
                            secondTransaction
                        )).isEqualTo(1);
                        first.rollback();
                    }
                }
            }
        } finally {
            commits.shutdownNow();
            assertThat(commits.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            environment.close();
        }
    }

    private static long commitSuccessCount(
        GatewayEnvironment environment,
        ProtocolAdapter<PostgresqlEvidence> adapter,
        TransactionRef transaction
    ) {
        return environment.journalSnapshot().entries().stream()
            .map(entry -> entry.event())
            .filter(InteractionObservationEvent.class::isInstance)
            .map(InteractionObservationEvent.class::cast)
            .filter(event -> event.evidence().schemaId().equals(
                adapter.evidenceCodec().schemaId()
            ))
            .map(event -> event.evidence().decode(adapter.evidenceCodec()))
            .filter(evidence -> evidence instanceof CommitSucceeded succeeded
                && succeeded.transaction().equals(transaction))
            .count();
    }

    private static int rowCount(Connection verification, long id) throws Exception {
        try (PreparedStatement query = verification.prepareStatement(
            "SELECT count(*) FROM proof_entry WHERE id = ?"
        )) {
            query.setLong(1, id);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private static CorrelationKey key(ByteBuffer value) {
        try {
            byte[] bytes = new byte[value.remaining()];
            value.get(bytes);
            return CorrelationKey.ofDigest(
                KEY_SCHEMA,
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static InetSocketAddress jdbcAddress(JdbcEndpoint endpoint) {
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

    private static final class GatewayEnvironment extends Environment {
        private final DatabaseComponent database;
        private final ClientComponent client;

        private GatewayEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing,
            DatabaseComponent database,
            ClientComponent client
        ) {
            super(topology, logging, routing);
            this.database = database;
            this.client = client;
        }

        private static GatewayEnvironment define(ProtocolAdapter<PostgresqlEvidence> adapter) {
            return define(
                adapter,
                new PostgresqlProtocolAdapter(ignored -> Optional.empty())
            );
        }

        private static GatewayEnvironment define(
            ProtocolAdapter<PostgresqlEvidence> adapter,
            ProtocolAdapter<PostgresqlEvidence> secondaryAdapter
        ) {
            EnvironmentBuilder builder = new EnvironmentBuilder().logging(
                EnvironmentLogging.logs()
                    .frameworkLevel(LogLevel.OFF)
                    .defaultComponentLevel(LogLevel.OFF)
                    .defaultConnectionLevel(LogLevel.OFF)
            );
            DatabaseComponent database = builder.component(DatabaseComponent.class);
            ClientComponent client = builder.component(ClientComponent.class);
            builder
                .connect(client.jdbc(), database.jdbc())
                .connect(client.secondaryJdbc(), database.secondaryJdbc());
            InteractionGateway gateway = new InteractionGateway();
            ConnectionRouting routing = ConnectionRouting.routed(
                client.jdbc().contract(),
                requiredObservationProfile(adapter),
                gateway.tcp(
                    endpoint(
                        PostgresqlGatewayCommitIT::jdbcAddress,
                        PostgresqlGatewayCommitIT::replaceAddress
                    ),
                    adapter,
                    LIMITS
                )
            ).withRoute(
                client.secondaryJdbc().contract(),
                requiredObservationProfile(secondaryAdapter),
                gateway.tcp(
                    endpoint(
                        PostgresqlGatewayCommitIT::jdbcAddress,
                        PostgresqlGatewayCommitIT::replaceAddress
                    ),
                    secondaryAdapter,
                    LIMITS
                )
            );
            return builder.build((topology, logging) -> new GatewayEnvironment(
                topology,
                logging,
                routing,
                database,
                client
            ));
        }

        private static RequiredObservationProfile requiredObservationProfile(
            ProtocolAdapter<PostgresqlEvidence> adapter
        ) {
            return new RequiredObservationProfile(
                adapter.evidenceCodec().schemaId(),
                Optional.of(TransactionRef.codec().schemaId()),
                Set.of(
                    Capability.CORRELATION_CONTRIBUTIONS,
                    Capability.SEMANTIC_CONTROL
                ),
                Set.of()
            );
        }

        private DatabaseOperations database() {
            return operations(database);
        }

        private ClientOperations client() {
            return operations(client);
        }

        private ClientComponent clientComponent() {
            return client;
        }
    }

    private static final class FailureCapturingAdapter
        implements ProtocolAdapter<PostgresqlEvidence> {
        private final PostgresqlProtocolAdapter delegate;
        private final AtomicReference<ProtocolAdapterException> failure =
            new AtomicReference<>();
        private final BlockingQueue<CommitDigest> commitDigests =
            new ArrayBlockingQueue<>(16);

        private FailureCapturingAdapter(PostgresqlProtocolAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract>
            observationContract() {
            return delegate.observationContract();
        }

        @Override
        public io.github.jacekkardys.systemproof.observation.EvidenceCodec<PostgresqlEvidence>
            evidenceCodec() {
            return delegate.evidenceCodec();
        }

        @Override
        public ProtocolSession<PostgresqlEvidence> openSession(ProtocolLimits limits) {
            return capturing(delegate.openSession(limits));
        }

        @Override
        public ProtocolSession<PostgresqlEvidence> openSession(
            ConnectionId connectionId,
            ProtocolLimits limits
        ) {
            return capturing(delegate.openSession(connectionId, limits));
        }

        private ProtocolSession<PostgresqlEvidence> capturing(
            ProtocolSession<PostgresqlEvidence> session
        ) {
            return direction -> capturing(session.openStream(direction));
        }

        private ProtocolStream<PostgresqlEvidence> capturing(
            ProtocolStream<PostgresqlEvidence> stream
        ) {
            return new ProtocolStream<>() {
                @Override
                public ProtocolDecodeResult<PostgresqlEvidence> decode(ByteBuffer bytes)
                    throws ProtocolAdapterException {
                    try {
                        ProtocolDecodeResult<PostgresqlEvidence> decoded =
                            stream.decode(bytes);
                        captureCommitDigest(decoded);
                        return decoded;
                    } catch (ProtocolAdapterException adapterFailure) {
                        failure.compareAndSet(null, adapterFailure);
                        throw adapterFailure;
                    }
                }

                @Override
                public void endOfInput(ByteBuffer bytes) throws ProtocolAdapterException {
                    try {
                        stream.endOfInput(bytes);
                    } catch (ProtocolAdapterException adapterFailure) {
                        failure.compareAndSet(null, adapterFailure);
                        throw adapterFailure;
                    }
                }
            };
        }

        private void captureCommitDigest(
            ProtocolDecodeResult<PostgresqlEvidence> decoded
        ) {
            if (decoded instanceof ProtocolDecodeResult.Complete<?> complete
                && complete.unit().evidence() instanceof CommitAttempt attempt
                && !commitDigests.offer(new CommitDigest(
                    attempt.transaction(),
                    digest(complete.unit().originalBytes())
                ))) {
                throw new IllegalStateException(
                    "PostgreSQL commit digest capacity was reached"
                );
            }
        }

        private CommitDigest awaitCommitDigest() throws Exception {
            CommitDigest captured = commitDigests.poll(10, TimeUnit.SECONDS);
            if (captured == null) {
                throw new IllegalStateException(
                    "PostgreSQL gateway did not capture a commit digest"
                );
            }
            return captured;
        }

        private void requireHealthy() {
            ProtocolAdapterException adapterFailure = failure.get();
            if (adapterFailure != null) {
                throw new IllegalStateException(
                    "PostgreSQL gateway adapter failed: " + adapterFailure.getMessage(),
                    adapterFailure
                );
            }
        }
    }

    private record CommitDigest(TransactionRef transaction, byte[] digest) {
        private CommitDigest {
            transaction = java.util.Objects.requireNonNull(
                transaction,
                "transaction must not be null"
            );
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    private interface DatabaseConfig extends ComponentConfig<DatabaseDriverConfig> {}

    private interface DatabaseDriverConfig extends DriverConfig {}

    @SystemComponent(type = "postgresql-gateway-database", driver = DatabaseDriver.class)
    private static final class DatabaseComponent
        extends AbstractComponent<DatabaseConfig, DatabaseOperations> {
        @PortContract("jdbc")
        @Communication.JdbcPostgresql
        private ProvidedPort<JdbcEndpoint> jdbc;

        @PortContract("jdbc-secondary")
        @Communication.JdbcPostgresql
        private ProvidedPort<JdbcEndpoint> secondaryJdbc;

        private DatabaseComponent() {}

        private ProvidedPort<JdbcEndpoint> jdbc() {
            return jdbc;
        }

        private ProvidedPort<JdbcEndpoint> secondaryJdbc() {
            return secondaryJdbc;
        }
    }

    private static final class DatabaseDriver
        implements ComponentDriver<DatabaseConfig, DatabaseOperations> {
        private DatabaseDriver(DatabaseDriverConfig configuration) {}

        @Override
        public ComponentRuntime<DatabaseOperations> start(
            AbstractComponent<DatabaseConfig, DatabaseOperations> component,
            DriverContext context
        ) {
            DatabaseComponent database = (DatabaseComponent) component;
            PostgreSQLContainer<?> postgres = durablePostgres();
            postgres.start();
            ReceiverProbe probe = ReceiverProbe.open(
                postgres.getHost(),
                postgres.getMappedPort(5432)
            );
            Secret<String> password = Secret.secret(postgres.getPassword());
            JdbcEndpoint internal = new JdbcEndpoint(
                "jdbc:postgresql://host.testcontainers.internal:" + probe.port()
                    + "/" + postgres.getDatabaseName(),
                postgres.getUsername(),
                password
            );
            JdbcEndpoint external = new JdbcEndpoint(
                "jdbc:postgresql://127.0.0.1:" + probe.port()
                    + "/" + postgres.getDatabaseName(),
                postgres.getUsername(),
                password
            );
            AutoCloseable resource = () -> closeDatabase(probe, postgres);
            return ComponentRuntime.<DatabaseOperations>runtime(resource)
                .provides(database.jdbc(), binding(internal, external))
                .provides(database.secondaryJdbc(), binding(internal, external))
                .operations(new DatabaseOperations(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    probe
                ))
                .build();
        }

        private static void closeDatabase(
            ReceiverProbe probe,
            PostgreSQLContainer<?> postgres
        ) throws Exception {
            Throwable failure = null;
            try {
                probe.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                postgres.stop();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static PostgreSQLContainer<?> durablePostgres() {
        return new PostgreSQLContainer<>("postgres:17.6-alpine")
            .withCommand(
                "postgres",
                "-c",
                "fsync=on",
                "-c",
                "synchronous_commit=on"
            );
    }

    private record DatabaseOperations(
        String url,
        String username,
        String password,
        ReceiverProbe probe
    ) {
        private Connection connectDirect() throws Exception {
            return DriverManager.getConnection(url, username, password);
        }
    }

    private interface ClientConfig extends ComponentConfig<ClientDriverConfig> {}

    private interface ClientDriverConfig extends DriverConfig {}

    @SystemComponent(type = "postgresql-gateway-client", driver = ClientDriver.class)
    private static final class ClientComponent
        extends AbstractComponent<ClientConfig, ClientOperations> {
        @PortContract("jdbc")
        @Communication.JdbcPostgresql
        @StartupPrerequisite
        private RequiredPort<JdbcEndpoint> jdbc;

        @PortContract("jdbc-secondary")
        @Communication.JdbcPostgresql
        @StartupPrerequisite
        private RequiredPort<JdbcEndpoint> secondaryJdbc;

        private ClientComponent() {}

        private RequiredPort<JdbcEndpoint> jdbc() {
            return jdbc;
        }

        private RequiredPort<JdbcEndpoint> secondaryJdbc() {
            return secondaryJdbc;
        }
    }

    private static final class ClientDriver
        implements ComponentDriver<ClientConfig, ClientOperations> {
        private ClientDriver(ClientDriverConfig configuration) {}

        @Override
        public ComponentRuntime<ClientOperations> start(
            AbstractComponent<ClientConfig, ClientOperations> component,
            DriverContext context
        ) {
            ClientComponent client = (ClientComponent) component;
            JdbcEndpoint endpoint = context.resolve(client.jdbc());
            JdbcEndpoint secondaryEndpoint = context.resolve(client.secondaryJdbc());
            return ComponentRuntime.<ClientOperations>runtime()
                .operations(new ClientOperations(endpoint, secondaryEndpoint))
                .build();
        }
    }

    private record ClientOperations(
        JdbcEndpoint endpoint,
        JdbcEndpoint secondaryEndpoint
    ) {
        private Connection connect() throws Exception {
            return connect(endpoint);
        }

        private Connection connectSecondary() throws Exception {
            return connect(secondaryEndpoint);
        }

        private static Connection connect(JdbcEndpoint endpoint) throws Exception {
            JdbcEndpoint localGateway = replaceAddress(
                endpoint,
                "127.0.0.1",
                jdbcAddress(endpoint).getPort()
            );
            return DriverManager.getConnection(
                localGateway.url(),
                localGateway.username(),
                localGateway.password().reveal()
            );
        }
    }

    private static final class ReceiverProbe implements AutoCloseable {
        private static final int BUFFER_LIMIT = 2 * 1024 * 1024;

        private final String targetHost;
        private final int targetPort;
        private final ServerSocket listener;
        private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicInteger commitAttempts = new AtomicInteger();
        private final AtomicInteger lastCommitByteCount = new AtomicInteger();
        private final AtomicReference<byte[]> lastCommitDigest = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final BlockingQueue<Integer> commitNotifications = new ArrayBlockingQueue<>(32);
        private final PostgresqlProtocolAdapter observer = new PostgresqlProtocolAdapter();

        private ReceiverProbe(String targetHost, int targetPort, ServerSocket listener) {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.listener = listener;
            tasks.submit(this::accept);
        }

        private static ReceiverProbe open(String targetHost, int targetPort) {
            try {
                ServerSocket listener = new ServerSocket();
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                return new ReceiverProbe(targetHost, targetPort, listener);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not open PostgreSQL receiver probe", failure);
            }
        }

        private int port() {
            return listener.getLocalPort();
        }

        private int commitAttempts() {
            return commitAttempts.get();
        }

        private int lastCommitByteCount() {
            return lastCommitByteCount.get();
        }

        private byte[] lastCommitDigest() {
            byte[] value = lastCommitDigest.get();
            return value == null ? null : value.clone();
        }

        private boolean hasPendingCommitNotification() {
            return !commitNotifications.isEmpty();
        }

        private int awaitCommitAttempt() throws Exception {
            Integer attempt = commitNotifications.poll(10, TimeUnit.SECONDS);
            if (attempt == null) {
                throw new IllegalStateException("Receiver did not observe the released commit unit");
            }
            return attempt;
        }

        private void requireHealthy() {
            Throwable probeFailure = failure.get();
            if (probeFailure != null) {
                throw new IllegalStateException(
                    "PostgreSQL receiver probe failed",
                    probeFailure
                );
            }
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket downstream = listener.accept();
                    Socket upstream = new Socket(targetHost, targetPort);
                    sockets.add(downstream);
                    sockets.add(upstream);
                    ProtocolSession<PostgresqlEvidence> session = observer.openSession(LIMITS);
                    Analyzer frontend = new Analyzer(
                        session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
                        true
                    );
                    Analyzer backend = new Analyzer(
                        session.openStream(FlowDirection.PROVIDER_TO_CONSUMER),
                        false
                    );
                    InputStream downstreamInput = downstream.getInputStream();
                    OutputStream downstreamOutput = downstream.getOutputStream();
                    InputStream upstreamInput = upstream.getInputStream();
                    OutputStream upstreamOutput = upstream.getOutputStream();
                    tasks.submit(() -> pump(
                        downstreamInput,
                        upstreamOutput,
                        frontend,
                        downstream,
                        upstream
                    ));
                    tasks.submit(() -> pump(
                        upstreamInput,
                        downstreamOutput,
                        backend,
                        downstream,
                        upstream
                    ));
                } catch (IOException failure) {
                    if (!listener.isClosed()) {
                        recordFailure(new IllegalStateException(
                            "PostgreSQL receiver probe accept failed",
                            failure
                        ));
                    }
                }
            }
        }

        private void pump(
            InputStream source,
            OutputStream destination,
            Analyzer analyzer,
            Socket downstream,
            Socket upstream
        ) {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = source.read(chunk)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    analyzer.accept(chunk, read);
                    destination.write(chunk, 0, read);
                    destination.flush();
                }
                analyzer.endOfInput();
            } catch (Exception failure) {
                if (!listener.isClosed()) {
                    recordFailure(new IllegalStateException(
                        "PostgreSQL receiver probe forwarding failed",
                        failure
                    ));
                }
            } finally {
                closeQuietly(downstream);
                closeQuietly(upstream);
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            sockets.forEach(ReceiverProbe::closeQuietly);
            tasks.shutdown();
            if (!tasks.awaitTermination(10, TimeUnit.SECONDS)) {
                tasks.shutdownNow();
                if (!tasks.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("PostgreSQL receiver probe did not stop");
                }
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort after the paired direction closes.
            }
        }

        private void recordFailure(Throwable candidate) {
            failure.updateAndGet(current -> {
                if (current == null) {
                    return candidate;
                }
                if (current.getCause() instanceof java.net.SocketException
                    && !(candidate.getCause() instanceof java.net.SocketException)) {
                    return candidate;
                }
                return current;
            });
        }

        private final class Analyzer {
            private final ProtocolStream<PostgresqlEvidence> stream;
            private final boolean frontend;
            private final byte[] pending = new byte[BUFFER_LIMIT];
            private int size;

            private Analyzer(
                ProtocolStream<PostgresqlEvidence> stream,
                boolean frontend
            ) {
                this.stream = stream;
                this.frontend = frontend;
            }

            private void accept(byte[] bytes, int length) throws Exception {
                if (length > pending.length - size) {
                    throw new IllegalStateException("Receiver probe buffer limit was reached");
                }
                System.arraycopy(bytes, 0, pending, size, length);
                size += length;
                while (true) {
                    ProtocolDecodeResult<PostgresqlEvidence> decoded = stream.decode(
                        ByteBuffer.wrap(pending, 0, size)
                    );
                    if (decoded instanceof ProtocolDecodeResult.NeedMoreData<?>) {
                        return;
                    }
                    ProtocolUnit<PostgresqlEvidence> unit =
                        ((ProtocolDecodeResult.Complete<PostgresqlEvidence>) decoded).unit();
                    byte[] original = unit.originalBytes();
                    if (!Arrays.equals(original, Arrays.copyOf(pending, original.length))) {
                        throw new IllegalStateException(
                            "Receiver probe adapter changed original bytes"
                        );
                    }
                    System.arraycopy(
                        pending,
                        original.length,
                        pending,
                        0,
                        size - original.length
                    );
                    size -= original.length;
                    if (frontend && unit.evidence() instanceof CommitAttempt) {
                        int attempt = commitAttempts.incrementAndGet();
                        lastCommitByteCount.set(original.length);
                        lastCommitDigest.set(digest(original));
                        if (!commitNotifications.offer(attempt)) {
                            throw new IllegalStateException(
                                "Receiver probe notification capacity was reached"
                            );
                        }
                    }
                }
            }

            private void endOfInput() throws Exception {
                stream.endOfInput(ByteBuffer.wrap(pending, 0, size));
            }
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
