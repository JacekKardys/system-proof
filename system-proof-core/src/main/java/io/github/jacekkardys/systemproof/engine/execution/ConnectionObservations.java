package io.github.jacekkardys.systemproof.engine.execution;

/**
 * Restricted traffic-observation capability bound to one exact runtime connection.
 *
 * <p>Each call creates a distinct physical-session identity. The capability neither exposes the
 * journal nor accepts a caller-supplied connection or session identity.
 */
public interface ConnectionObservations {
    InteractionSession openSession();
}
