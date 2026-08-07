package io.github.jacekkardys.systemproof.control;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
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
public final class SemanticInteractionSelector<T> {
    private final ConnectionId connectionId;
    private final FlowDirection direction;
    private final EvidenceCodec<T> codec;
    private final EvidenceSchemaId evidenceSchema;
    private final Predicate<? super T> matcher;
    private final ProofSubjectRef proofSubject;
    private final NativeFlowConstraint<T, ?> nativeFlowConstraint;

    private SemanticInteractionSelector(
        ConnectionId connectionId,
        FlowDirection direction,
        EvidenceCodec<T> codec,
        Predicate<? super T> matcher,
        ProofSubjectRef proofSubject,
        NativeFlowConstraint<T, ?> nativeFlowConstraint
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
        this.nativeFlowConstraint = nativeFlowConstraint;
    }

    public static <T> SemanticInteractionSelector<T> matching(
        ConnectionId connectionId,
        FlowDirection direction,
        EvidenceCodec<T> codec,
        Predicate<? super T> matcher
    ) {
        return new SemanticInteractionSelector<>(
            connectionId,
            direction,
            codec,
            matcher,
            null,
            null
        );
    }

    /** Returns a selector constrained to one subject from the same environment execution. */
    public SemanticInteractionSelector<T> forSubject(ProofSubjectRef subject) {
        return new SemanticInteractionSelector<>(
            connectionId,
            direction,
            codec,
            matcher,
            Objects.requireNonNull(subject, "subject must not be null"),
            nativeFlowConstraint
        );
    }

    /**
     * Returns a selector constrained through one subject-owned native flow reference.
     *
     * <p>The correlation key must already be armed for the selected proof subject. The extractor
     * is evaluated against the current typed evidence and its result is compared with the unique
     * native reference previously contributed for that subject and key. The originating
     * contribution and candidate must share the exact logical connection and physical gateway
     * session; opposite protocol directions on that session remain composable.
     */
    public <R> SemanticInteractionSelector<T> through(
        CorrelationKey correlationKey,
        EvidenceCodec<R> nativeReferenceCodec,
        Function<? super T, ? extends R> nativeReference
    ) {
        if (proofSubject == null) {
            throw new IllegalStateException(
                "A native-flow selector requires a proof subject"
            );
        }
        if (nativeFlowConstraint != null) {
            throw new IllegalStateException(
                "A native-flow selector is already configured"
            );
        }
        return new SemanticInteractionSelector<>(
            connectionId,
            direction,
            codec,
            matcher,
            proofSubject,
            new NativeFlowConstraint<>(
                Objects.requireNonNull(correlationKey, "correlationKey must not be null"),
                Objects.requireNonNull(
                    nativeReferenceCodec,
                    "nativeReferenceCodec must not be null"
                ),
                Objects.requireNonNull(nativeReference, "nativeReference must not be null")
            )
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

    /** Returns the typed codec supplied when this immutable selector was declared. */
    public EvidenceCodec<T> evidenceCodec() {
        return codec;
    }

    public Optional<ProofSubjectRef> proofSubject() {
        return Optional.ofNullable(proofSubject);
    }

    /** Returns the correlation key used by a native-flow constraint, when configured. */
    public Optional<CorrelationKey> nativeFlowCorrelationKey() {
        return nativeFlowConstraint == null
            ? Optional.empty()
            : Optional.of(nativeFlowConstraint.correlationKey);
    }

    /** Returns the native-flow reference schema required by this selector, when configured. */
    public Optional<EvidenceSchemaId> nativeFlowReferenceSchema() {
        return nativeFlowConstraint == null
            ? Optional.empty()
            : Optional.of(nativeFlowConstraint.codec.schemaId());
    }

    /** Returns the typed native-reference codec supplied by {@link #through}, when configured. */
    public Optional<EvidenceCodec<?>> nativeFlowReferenceCodec() {
        return nativeFlowConstraint == null
            ? Optional.empty()
            : Optional.of(nativeFlowConstraint.codec);
    }

    /** Evaluates only the typed evidence step after infrastructure schema validation. */
    public boolean matches(T evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        return matcher.test(evidence);
    }

    /**
     * Compares the native reference extracted from typed evidence with a codec-decoded reference.
     */
    public boolean matchesNativeFlow(
        T evidence,
        Object resolvedNativeReference
    ) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(
            resolvedNativeReference,
            "resolvedNativeReference must not be null"
        );
        if (nativeFlowConstraint == null) {
            throw new IllegalStateException(
                "This selector has no native-flow constraint"
            );
        }
        return nativeFlowConstraint.matches(evidence, resolvedNativeReference);
    }

    @Override
    public String toString() {
        return "SemanticInteractionSelector[connectionId=" + connectionId
            + ", direction=" + direction
            + ", evidenceSchema=" + evidenceSchema
            + ", subjectConstrained=" + (proofSubject != null)
            + ", nativeFlowConstrained=" + (nativeFlowConstraint != null) + "]";
    }

    private static final class NativeFlowConstraint<T, R> {
        private final CorrelationKey correlationKey;
        private final EvidenceCodec<R> codec;
        private final Function<? super T, ? extends R> nativeReference;

        private NativeFlowConstraint(
            CorrelationKey correlationKey,
            EvidenceCodec<R> codec,
            Function<? super T, ? extends R> nativeReference
        ) {
            this.correlationKey = correlationKey;
            this.codec = codec;
            this.nativeReference = nativeReference;
        }

        @SuppressWarnings("unchecked")
        private boolean matches(T evidence, Object resolvedNativeReference) {
            R extracted = Objects.requireNonNull(
                nativeReference.apply(evidence),
                "Native-flow reference extractor returned null"
            );
            R resolved = (R) Objects.requireNonNull(
                resolvedNativeReference,
                "resolvedNativeReference must not be null"
            );
            byte[] extractedEncoded = Objects.requireNonNull(
                codec.encode(extracted),
                "Native-reference codec returned null encoded evidence"
            );
            byte[] resolvedEncoded = Objects.requireNonNull(
                codec.encode(resolved),
                "Native-reference codec returned null encoded evidence"
            );
            return java.util.Arrays.equals(extractedEncoded, resolvedEncoded);
        }
    }
}
