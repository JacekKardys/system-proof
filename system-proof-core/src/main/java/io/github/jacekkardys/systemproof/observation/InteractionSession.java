package io.github.jacekkardys.systemproof.observation;

import io.github.jacekkardys.systemproof.proof.CorrelationContribution;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.InteractionRef;

/**
 * Restricted observation capability for one physical transport session.
 *
 * <p>The caller supplies only the topological flow direction and typed evidence. The capability
 * allocates the stream-local ordinal, creates the complete interaction identity, captures the
 * evidence, appends the event, and returns the assigned reference.
 */
public interface InteractionSession {
    <T> InteractionRef observe(
        FlowDirection direction,
        EvidenceCodec<T> codec,
        T evidence
    );

    /**
     * Publishes one immutable correlation contribution for an interaction returned by this
     * session.
     *
     * <p>The default preserves compatibility for protocol units without correlation. Environment
     * sessions override it with a connection- and session-validated publication boundary.
     */
    default void correlate(
        InteractionRef interactionRef,
        CorrelationContribution<?> contribution
    ) {
        throw new UnsupportedOperationException(
            "This interaction session does not support correlation contributions"
        );
    }
}
