/**
 * Defines environment declaration, assembly, lifecycle, routing, execution, and inspection.
 *
 * <p>The supported surface consists of the environment facade and builder, immutable topology,
 * declared routing policy, and the narrow routing/session extension SPI. Detached execution state
 * lives in {@code environment.state}.
 * Package-private types own all mutable construction, lifecycle, component, connection, proof,
 * journal, redaction, logging-emission, and cleanup state.
 *
 * <p>Environment execution depends on stable component, configuration, diagnostics, endpoint,
 * journal, observation, proof, and topology contracts. Those contracts never depend back on the
 * mutable environment implementation.
 */
package io.github.jacekkardys.systemproof.environment;
