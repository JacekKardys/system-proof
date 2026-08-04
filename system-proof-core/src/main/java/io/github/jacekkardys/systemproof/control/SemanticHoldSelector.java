package io.github.jacekkardys.systemproof.control;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/**
 * Immutable typed selector for one exact connection and topological flow direction.
 *
 * <p>The matcher must be pure, fast, non-blocking, side-effect free, and safe for synchronous
 * invocation on the gateway decision path. It is invoked only after connection, direction, and
 * evidence-schema equality have been established. Codec or matcher failures fail the control
 * closed and never authorize forwarding.
 */
public final class SemanticHoldSelector<T> {
    private final ConnectionId connectionId;
    private final FlowDirection direction;
    private final EvidenceCodec<T> codec;
    private final EvidenceSchemaId evidenceSchema;
    private final Predicate<? super T> matcher;
    private final ProofSubjectRef proofSubject;

    private SemanticHoldSelector(
        ConnectionId connectionId,
        FlowDirection direction,
        EvidenceCodec<T> codec,
        Predicate<? super T> matcher,
        ProofSubjectRef proofSubject
    ) {
        this.connectionId = Objects.requireNonNull(
            connectionId,
            "connectionId must not be null"
        );
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        evidenceSchema = Objects.requireNonNull(
            codec.schemaId(),
            "codec schemaId must not be null"
        );
        this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
        this.proofSubject = proofSubject;
    }

    public static <T> SemanticHoldSelector<T> matching(
        ConnectionId connectionId,
        FlowDirection direction,
        EvidenceCodec<T> codec,
        Predicate<? super T> matcher
    ) {
        return new SemanticHoldSelector<>(connectionId, direction, codec, matcher, null);
    }

    /** Returns a selector constrained to one subject from the same environment execution. */
    public SemanticHoldSelector<T> forSubject(ProofSubjectRef subject) {
        return new SemanticHoldSelector<>(
            connectionId,
            direction,
            codec,
            matcher,
            Objects.requireNonNull(subject, "subject must not be null")
        );
    }

    public ConnectionId connectionId() {
        return connectionId;
    }

    public FlowDirection direction() {
        return direction;
    }

    public EvidenceSchemaId evidenceSchema() {
        return evidenceSchema;
    }

    public Optional<ProofSubjectRef> proofSubject() {
        return Optional.ofNullable(proofSubject);
    }

    /** Evaluates only the typed evidence step after schema equality has been checked. */
    public boolean matchesEvidence(EvidenceSnapshot evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!evidenceSchema.equals(evidence.schemaId())) {
            throw new IllegalArgumentException("Evidence schema does not match this selector");
        }
        return matcher.test(evidence.decode(codec));
    }

    @Override
    public String toString() {
        return "SemanticHoldSelector[connectionId=" + connectionId
            + ", direction=" + direction
            + ", evidenceSchema=" + evidenceSchema
            + ", subjectConstrained=" + (proofSubject != null) + "]";
    }
}
