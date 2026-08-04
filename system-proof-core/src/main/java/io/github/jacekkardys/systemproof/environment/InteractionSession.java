package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.observation.RecordedInteraction;

/**
 * Restricted observation capability for one physical transport session.
 *
 * <p>The caller supplies only the topological flow direction and typed evidence. The capability
 * allocates the stream-local ordinal, creates the complete interaction identity, captures the
 * evidence, appends the event, and returns the assigned reference.
 */
public interface InteractionSession {
    /** Records once and returns only the assigned identity to legacy observation callers. */
    default <T> InteractionRef observe(
        FlowDirection direction,
        EvidenceCodec<T> codec,
        T evidence
    ) {
        return record(direction, codec, evidence).interactionRef();
    }

    /**
     * Records evidence and returns the captured read-only interaction model used by the decision
     * handshake.
     *
     * <p>The implementation must capture evidence exactly once and use that same immutable snapshot
     * for journal publication and the returned model.
     */
    <T> RecordedInteraction record(
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
