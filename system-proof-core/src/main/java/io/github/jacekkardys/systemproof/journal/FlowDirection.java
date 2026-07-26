package io.github.jacekkardys.systemproof.journal;

/**
 * Topological direction of one observed protocol unit on a logical connection.
 *
 * <p>The direction is independent of the component or route implementation that observed the
 * traffic.
 */
public enum FlowDirection {
    CONSUMER_TO_PROVIDER,
    PROVIDER_TO_CONSUMER
}
