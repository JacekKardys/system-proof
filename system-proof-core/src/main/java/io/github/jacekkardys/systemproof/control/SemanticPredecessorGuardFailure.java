package io.github.jacekkardys.systemproof.control;

/** Secret-safe failure classification for a semantic predecessor guard. */
public enum SemanticPredecessorGuardFailure {
    SELECTOR_EVALUATION,
    CORRELATION_INVALIDATED,
    WRITE_FAILURE,
    SESSION_ABANDONED,
    REQUIRED_OBSERVATION_FAILURE,
    INTERNAL_FAILURE
}
