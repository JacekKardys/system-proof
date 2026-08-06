package io.github.jacekkardys.systemproof.control;

/** Exact semantic boundary that a predecessor interaction must establish. */
public enum SemanticPredecessorBoundary {
    /** The protocol adapter observed and recorded the protocol's positive confirmation. */
    CONFIRMED,

    /** The exact gateway permit reported a successful write and flush. */
    FORWARDED
}
