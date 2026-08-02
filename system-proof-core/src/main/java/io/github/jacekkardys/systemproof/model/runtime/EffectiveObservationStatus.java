package io.github.jacekkardys.systemproof.model.runtime;

/** Effective protocol-observation state exposed by an immutable runtime connection snapshot. */
public enum EffectiveObservationStatus {
    /** Observation was not requested. */
    DISABLED,

    /** Observation was requested but route preparation has not completed. */
    PENDING,

    /** Complete protocol units are recorded and decided before their original bytes are forwarded. */
    ACTIVE,

    /** Optional observation is unsupported and the route is explicitly transparent. */
    UNSUPPORTED,

    /** A previously active optional observation path is no longer trustworthy. */
    DEGRADED,

    /** Required observation failed closed. */
    FAILED,

    /** A previously active route has completed deterministic shutdown. */
    INACTIVE
}
