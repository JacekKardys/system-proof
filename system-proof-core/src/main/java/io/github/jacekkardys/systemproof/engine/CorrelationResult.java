package io.github.jacekkardys.systemproof.engine;

import java.util.Objects;
import io.github.jacekkardys.systemproof.journal.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.journal.InteractionRef;

/**
 * Typed result for one proof subject and one armed correlation key.
 *
 * <p>Only {@link Unique} exposes a native reference or interaction identity. Missing and
 * ambiguous results cannot accidentally yield a best-effort candidate.
 */
public sealed interface CorrelationResult<T>
    permits CorrelationResult.Missing, CorrelationResult.Unique, CorrelationResult.Ambiguous {

    CorrelationCardinality cardinality();

    record Missing<T>() implements CorrelationResult<T> {
        @Override
        public CorrelationCardinality cardinality() {
            return CorrelationCardinality.MISSING;
        }
    }

    record Unique<T>(
        InteractionRef interactionRef,
        EvidenceSchemaId nativeReferenceSchema,
        T nativeReference
    ) implements CorrelationResult<T> {
        public Unique {
            interactionRef = Objects.requireNonNull(
                interactionRef,
                "interactionRef must not be null"
            );
            nativeReferenceSchema = Objects.requireNonNull(
                nativeReferenceSchema,
                "nativeReferenceSchema must not be null"
            );
            nativeReference = Objects.requireNonNull(
                nativeReference,
                "nativeReference must not be null"
            );
        }

        @Override
        public CorrelationCardinality cardinality() {
            return CorrelationCardinality.UNIQUE;
        }

        @Override
        public String toString() {
            return "Unique[interactionRef=" + interactionRef
                + ", nativeReferenceSchema=" + nativeReferenceSchema + "]";
        }
    }

    record Ambiguous<T>() implements CorrelationResult<T> {
        @Override
        public CorrelationCardinality cardinality() {
            return CorrelationCardinality.AMBIGUOUS;
        }
    }
}
