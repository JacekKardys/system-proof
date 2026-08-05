package io.github.jacekkardys.systemproof.http;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Bounded plaintext HTTP/1.1 adapter for the characterized Jasmin callback flow. */
public final class HttpProtocolAdapter implements ProtocolAdapter<HttpEvidence> {
    private static final ProtocolObservationContract OBSERVATION_CONTRACT =
        observationContract(false);
    private static final ProtocolObservationContract CORRELATING_OBSERVATION_CONTRACT =
        observationContract(true);

    private final HttpProtocolLimits httpLimits;
    private final HttpRequestCorrelation requestCorrelation;
    private final boolean correlationContributions;
    private final AtomicLong nextSessionOrdinal = new AtomicLong(1);

    /** Creates an adapter with default HTTP limits and no correlation contributions. */
    public HttpProtocolAdapter() {
        this(HttpProtocolLimits.defaults(), HttpRequestCorrelation.none(), false);
    }

    /** Creates an adapter with default HTTP limits and one ephemeral request policy. */
    public HttpProtocolAdapter(HttpRequestCorrelation requestCorrelation) {
        this(HttpProtocolLimits.defaults(), requestCorrelation, true);
    }

    /** Creates an adapter with explicit HTTP limits and one ephemeral request policy. */
    public HttpProtocolAdapter(
        HttpProtocolLimits httpLimits,
        HttpRequestCorrelation requestCorrelation
    ) {
        this(httpLimits, requestCorrelation, true);
    }

    private HttpProtocolAdapter(
        HttpProtocolLimits httpLimits,
        HttpRequestCorrelation requestCorrelation,
        boolean correlationContributions
    ) {
        this.httpLimits = Objects.requireNonNull(httpLimits, "httpLimits must not be null");
        this.requestCorrelation = Objects.requireNonNull(
            requestCorrelation,
            "requestCorrelation must not be null"
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
    public EvidenceCodec<HttpEvidence> evidenceCodec() {
        return HttpEvidenceCodec.INSTANCE;
    }

    @Override
    public ProtocolSession<HttpEvidence> openSession(ProtocolLimits limits) {
        return openSession(null, limits);
    }

    @Override
    public ProtocolSession<HttpEvidence> openSession(
        ConnectionId connectionId,
        ProtocolLimits limits
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        long ordinal = nextSessionOrdinal.getAndUpdate(value ->
            value == Long.MAX_VALUE ? Long.MIN_VALUE : value + 1
        );
        if (ordinal < 1) {
            throw new IllegalStateException("HTTP adapter session identity space exhausted");
        }
        return new HttpProtocolSession(
            ordinal,
            limits,
            httpLimits,
            requestCorrelation
        );
    }

    private static ProtocolObservationContract observationContract(
        boolean correlationContributions
    ) {
        return new ProtocolObservationContract(
            "http",
            "http",
            URI.class,
            HttpEvidenceCodec.INSTANCE.schemaId(),
            correlationContributions
                ? Optional.of(HttpExchangeRef.codec().schemaId())
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
