package io.github.jacekkardys.systemproof.postgresql;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import io.github.jacekkardys.systemproof.endpoint.JdbcEndpoint;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Feature;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Prerequisite;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/**
 * Bounded PostgreSQL v3 plaintext adapter for the characterized pgJDBC subset.
 *
 * <p>One adapter instance assigns monotonically increasing identities to physical protocol
 * sessions and retains only a bounded set of pending one-shot durability challenges. It owns no
 * sockets, journal, raw SQL, bind values, or backend-PID registry.
 */
public final class PostgresqlProtocolAdapter implements ProtocolAdapter<PostgresqlEvidence> {
    private static final int MAXIMUM_PENDING_DURABILITY_CHALLENGES = 64;
    private static final ProtocolObservationContract OBSERVATION_CONTRACT =
        observationContract(false);
    private static final ProtocolObservationContract CORRELATING_OBSERVATION_CONTRACT =
        observationContract(true);
    private final PostgresqlWriteCorrelation writeCorrelation;
    private final boolean correlationContributions;
    private final AtomicLong nextSessionOrdinal = new AtomicLong(1);
    private final Map<String, DurabilityChallenge> pendingDurabilityChallenges =
        new HashMap<>();

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
        SessionDurability durability = new SessionDurability(connectionId);
        return new PostgresqlProtocolSession(
            ordinal,
            limits,
            writeCorrelation,
            durability
        );
    }

    synchronized DurabilityChallenge beginDurabilityChallenge(ConnectionId connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        if (pendingDurabilityChallenges.size()
            >= MAXIMUM_PENDING_DURABILITY_CHALLENGES) {
            throw new IllegalStateException(
                "PostgreSQL durability challenge limit was reached"
            );
        }
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (pendingDurabilityChallenges.containsKey(token));
        DurabilityChallenge challenge = new DurabilityChallenge(connectionId, token);
        pendingDurabilityChallenges.put(token, challenge);
        return challenge;
    }

    synchronized boolean applyDurability(
        DurabilityChallenge challenge,
        boolean satisfied
    ) {
        challenge = Objects.requireNonNull(challenge, "challenge must not be null");
        if (!pendingDurabilityChallenges.remove(challenge.token, challenge)) {
            return false;
        }
        challenge.closed = true;
        ChallengeClaim claim = challenge.claim;
        return claim != null
            && claim.session().authorize(claim.transaction(), satisfied);
    }

    private synchronized boolean claimDurabilityChallenge(
        String token,
        ConnectionId connectionId,
        SessionDurability session,
        TransactionRef transaction
    ) {
        DurabilityChallenge challenge = pendingDurabilityChallenges.get(token);
        if (challenge == null
            || challenge.closed
            || challenge.claim != null
            || connectionId == null
            || !challenge.connectionId.equals(connectionId)) {
            return false;
        }
        challenge.claim = new ChallengeClaim(session, transaction);
        return true;
    }

    final class SessionDurability {
        private final ConnectionId connectionId;
        private int backendPid;
        private boolean backendIdentityObserved;
        private boolean terminal;
        private TransactionRef authorizedTransaction;

        private SessionDurability(ConnectionId connectionId) {
            this.connectionId = connectionId;
        }

        synchronized void observeBackendPid(int candidate)
            throws ProtocolAdapterException {
            if (candidate <= 0) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "Invalid PostgreSQL backend identity"
                );
            }
            if (backendIdentityObserved) {
                if (backendPid != candidate) {
                    throw new ProtocolAdapterException(
                        ProtocolFailureKind.DESYNCHRONIZATION,
                        "PostgreSQL backend identity changed within one session"
                    );
                }
                return;
            }
            backendPid = candidate;
            backendIdentityObserved = true;
        }

        boolean claimChallenge(String token, TransactionRef transaction) {
            synchronized (this) {
                if (terminal || !backendIdentityObserved) {
                    return false;
                }
            }
            return claimDurabilityChallenge(
                token,
                connectionId,
                this,
                Objects.requireNonNull(transaction, "transaction must not be null")
            );
        }

        synchronized boolean authorize(TransactionRef transaction, boolean satisfied) {
            if (terminal) {
                return false;
            }
            authorizedTransaction = satisfied
                ? Objects.requireNonNull(transaction, "transaction must not be null")
                : null;
            return true;
        }

        synchronized boolean authorized(TransactionRef transaction) {
            return Objects.equals(authorizedTransaction, transaction);
        }

        synchronized void revoke() {
            authorizedTransaction = null;
        }

        synchronized void terminal() {
            authorizedTransaction = null;
            terminal = true;
        }
    }

    final class DurabilityChallenge implements AutoCloseable {
        private final ConnectionId connectionId;
        private final String token;
        private ChallengeClaim claim;
        private boolean closed;

        private DurabilityChallenge(ConnectionId connectionId, String token) {
            this.connectionId = connectionId;
            this.token = token;
        }

        String token() {
            return token;
        }

        @Override
        public void close() {
            synchronized (PostgresqlProtocolAdapter.this) {
                if (closed) {
                    return;
                }
                pendingDurabilityChallenges.remove(token, this);
                closed = true;
            }
        }
    }

    private record ChallengeClaim(
        SessionDurability session,
        TransactionRef transaction
    ) {}

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
                    Capability.SEMANTIC_CONTROL,
                    Capability.DURABLE_SUCCESS
                )
                : Set.of(
                    Capability.SEMANTIC_CONTROL,
                    Capability.DURABLE_SUCCESS
                ),
            Set.of(Prerequisite.EXACT_SESSION_DURABILITY),
            Set.of(
                Feature.ENCRYPTED_TRANSPORT,
                Feature.GENERAL_PIPELINING
            )
        );
    }
}
