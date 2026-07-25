package io.github.jacekkardys.systemproof.model;

/** How a runtime connection currently supplies an endpoint to its consumer. */
public enum RoutingMode {
    /**
     * The consumer receives the provider's internal endpoint directly.
     *
     * <p>No gateway interposition or traffic-observation guarantee is implied.
     */
    DIRECT
}
