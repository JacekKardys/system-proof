package io.github.jacekkardys.systemproof.environment;

/**
 * Marker SPI for both a route provider and its materialized route resource when they implement the
 * recorded-interaction forwarding-permit handshake required by semantic controls.
 *
 * <p>Declaring the marker on a provider enables pre-start validation for a routed connection with
 * required observation. The environment also requires the returned route resource to implement
 * the marker and report active observation before the connection can start.
 */
public interface SemanticControlRouteCapability {}
