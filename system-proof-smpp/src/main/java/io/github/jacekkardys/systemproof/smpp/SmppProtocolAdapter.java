package io.github.jacekkardys.systemproof.smpp;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.github.jacekkardys.systemproof.endpoint.SmppEndpoint;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapter;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/** Bounded fail-closed SMPP 3.4 adapter for the characterized reference flow. */
public final class SmppProtocolAdapter implements ProtocolAdapter<SmppEvidence> {
    private static final ProtocolObservationContract OBSERVATION_CONTRACT =
        observationContract(false);
    private static final ProtocolObservationContract CORRELATING_OBSERVATION_CONTRACT =
        observationContract(true);

    private final SmppProtocolLimits smppLimits;
    private final SmppDeliverCorrelation deliverCorrelation;
    private final boolean correlationContributions;
    private final AtomicLong nextSessionOrdinal = new AtomicLong(1);

    /** Creates an adapter with bounded defaults and no correlation contributions. */
    public SmppProtocolAdapter() {
        this(SmppProtocolLimits.defaults(), SmppDeliverCorrelation.none(), false);
    }

    /** Creates an adapter with bounded defaults and one ephemeral deliver_sm policy. */
    public SmppProtocolAdapter(SmppDeliverCorrelation deliverCorrelation) {
        this(SmppProtocolLimits.defaults(), deliverCorrelation, true);
    }

    /** Creates an adapter with explicit limits and one ephemeral deliver_sm policy. */
    public SmppProtocolAdapter(
        SmppProtocolLimits smppLimits,
        SmppDeliverCorrelation deliverCorrelation
    ) {
        this(smppLimits, deliverCorrelation, true);
    }

    private SmppProtocolAdapter(
        SmppProtocolLimits smppLimits,
        SmppDeliverCorrelation deliverCorrelation,
        boolean correlationContributions
    ) {
        this.smppLimits = Objects.requireNonNull(smppLimits, "smppLimits must not be null");
        this.deliverCorrelation = Objects.requireNonNull(
            deliverCorrelation,
            "deliverCorrelation must not be null"
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
    public EvidenceCodec<SmppEvidence> evidenceCodec() {
        return SmppEvidenceCodec.INSTANCE;
    }

    @Override
    public ProtocolSession<SmppEvidence> openSession(ProtocolLimits limits) {
        return openSession(null, limits);
    }

    @Override
    public ProtocolSession<SmppEvidence> openSession(
        ConnectionId connectionId,
        ProtocolLimits limits
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        long ordinal = nextSessionOrdinal.getAndUpdate(value ->
            value == Long.MAX_VALUE ? Long.MIN_VALUE : value + 1
        );
        if (ordinal < 1) {
            throw new IllegalStateException("SMPP adapter session identity space exhausted");
        }
        return new SmppProtocolSession(
            ordinal,
            limits,
            smppLimits,
            deliverCorrelation
        );
    }

    private static ProtocolObservationContract observationContract(
        boolean correlationContributions
    ) {
        return new ProtocolObservationContract(
            "smpp",
            "smpp",
            SmppEndpoint.class,
            SmppEvidenceCodec.INSTANCE.schemaId(),
            correlationContributions
                ? Optional.of(SmppExchangeRef.codec().schemaId())
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
