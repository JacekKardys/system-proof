package io.github.jacekkardys.systemproof.control;

/** Secret-safe failure classification for a semantic hold journal fact. */
public enum SemanticHoldFailure {
    SELECTOR_EVALUATION,
    AMBIGUOUS_MATCH,
    WRITE_FAILURE,
    SESSION_ABANDONED,
    INTERNAL_FAILURE
}
