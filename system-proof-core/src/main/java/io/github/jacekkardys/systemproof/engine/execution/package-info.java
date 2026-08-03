/**
 * Defines the environment facade, runtime-coupled extension SPI, and one execution's mutable
 * component, connection, proof, and cleanup state.
 *
 * <p>Closely coupled runtime collaborators intentionally share this package so their lifecycle
 * mutators remain package-private. Public route providers receive only a connection-bound context;
 * route selection, endpoint lookup, preparation, and resource ownership stay internal. Stable
 * evidence, identity, proof, and result contracts live in {@code observation} and {@code proof}.
 */
package io.github.jacekkardys.systemproof.engine.execution;
