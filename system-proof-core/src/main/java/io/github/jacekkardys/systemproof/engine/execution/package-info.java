/**
 * Executes one environment lifecycle and owns its mutable component, connection, proof, and
 * cleanup state.
 *
 * <p>Closely coupled runtime collaborators intentionally share this package so their lifecycle
 * mutators remain package-private. Stable configuration, SPI, identity, and result contracts live
 * in {@code routing}, {@code observation}, and {@code proof} instead.
 */
package io.github.jacekkardys.systemproof.engine.execution;
