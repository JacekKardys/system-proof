package io.github.jacekkardys.systemproof.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/**
 * Bounded PostgreSQL v3 plaintext adapter for the characterized pgJDBC subset.
 *
 * <p>One adapter instance assigns monotonically increasing identities to physical protocol
 * sessions. It owns no sockets, journal, raw SQL, bind values, or backend-PID registry.
 */
public final class PostgresqlProtocolAdapter implements ProtocolAdapter<PostgresqlEvidence> {
    private static final ProtocolObservationContract OBSERVATION_CONTRACT =
        observationContract(false);
    private static final ProtocolObservationContract CORRELATING_OBSERVATION_CONTRACT =
        observationContract(true);
    private final PostgresqlWriteCorrelation writeCorrelation;
    private final boolean correlationContributions;
    private final AtomicLong nextSessionOrdinal = new AtomicLong(1);

    /** Creates an adapter that emits no write-correlation contributions. */
    public PostgresqlProtocolAdapter() {
        this(PostgresqlWriteCorrelation.none(), false);
    }

    /**
     * Creates an adapter with a synchronous, ephemeral correlation policy for supported writes.
     *
     * @param writeCorrelation policy invoked only while the current bind unit is being decoded
     */
    public PostgresqlProtocolAdapter(PostgresqlWriteCorrelation writeCorrelation) {
        this(writeCorrelation, true);
    }

    private PostgresqlProtocolAdapter(
        PostgresqlWriteCorrelation writeCorrelation,
        boolean correlationContributions
    ) {
        this.writeCorrelation = Objects.requireNonNull(
            writeCorrelation,
            "writeCorrelation must not be null"
        );
        this.correlationContributions = correlationContributions;
    }

    @Override
    public Optional<ProtocolObservationContract> observationContract() {
        return Optional.of(correlationContributions
            ? CORRELATING_OBSERVATION_CONTRACT
            : OBSERVATION_CONTRACT);
    }

    @Override
    public EvidenceCodec<PostgresqlEvidence> evidenceCodec() {
        return PostgresqlEvidenceCodec.INSTANCE;
    }

    @Override
    public ProtocolSession<PostgresqlEvidence> openSession(ProtocolLimits limits) {
        return openSession(null, limits);
    }

    @Override
    public ProtocolSession<PostgresqlEvidence> openSession(
        ConnectionId connectionId,
        ProtocolLimits limits
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        long ordinal = nextSessionOrdinal.getAndUpdate(value ->
            value == Long.MAX_VALUE ? Long.MIN_VALUE : value + 1
        );
        if (ordinal < 1) {
            throw new IllegalStateException("PostgreSQL adapter session identity space exhausted");
        }
        return new PostgresqlProtocolSession(
            ordinal,
            limits,
            writeCorrelation
        );
    }

    private static ProtocolObservationContract observationContract(
        boolean correlationContributions
    ) {
        return new ProtocolObservationContract(
            "jdbc-postgresql",
            "jdbc:postgresql",
            JdbcEndpoint.class,
            PostgresqlEvidenceCodec.INSTANCE.schemaId(),
            correlationContributions
                ? Optional.of(TransactionRef.codec().schemaId())
                : Optional.empty(),
            correlationContributions
                ? Set.of(
                    Capability.CORRELATION_CONTRIBUTIONS,
                    Capability.SEMANTIC_CONTROL
                )
                : Set.of(Capability.SEMANTIC_CONTROL),
            Set.of()
        );
    }
}
