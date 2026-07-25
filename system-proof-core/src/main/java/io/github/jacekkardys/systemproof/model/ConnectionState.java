package io.github.jacekkardys.systemproof.model;

/**
 * Runtime lifecycle of one materialized connection.
 *
 * <p>Legal transitions are enforced by the runtime connection: {@code DECLARED -> STARTING ->
 * RUNNING -> STOPPING -> STOPPED}. Failure may replace an active transition and is terminal.
 * Closing before startup transitions directly from {@code DECLARED} to {@code STOPPED}.
 */
public enum ConnectionState {
    DECLARED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
