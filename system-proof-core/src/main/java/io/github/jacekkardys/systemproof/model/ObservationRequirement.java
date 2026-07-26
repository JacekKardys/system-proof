package io.github.jacekkardys.systemproof.model;

/** Required observation semantics for one runtime connection route. */
public enum ObservationRequirement {
    /** Route traffic transparently without claiming protocol observation. */
    DISABLED,

    /** Observe when the selected route supports it and report unsupported or degraded operation. */
    OPTIONAL,

    /** Establish trustworthy observe-before-forward behavior or fail closed. */
    REQUIRED
}
