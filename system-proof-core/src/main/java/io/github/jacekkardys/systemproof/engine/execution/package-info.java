/**
 * Defines the environment facade, runtime-coupled extension SPI, and one execution's mutable
 * component, connection, journal, redaction, proof, and cleanup state.
 *
 * <p>Closely coupled runtime collaborators intentionally share this package so their lifecycle
 * mutators remain package-private. Public route providers receive only a connection-bound context;
 * route selection, endpoint lookup, preparation, and resource ownership stay internal. Stable
 * evidence, identity, proof, and result contracts live in {@code observation} and {@code proof}.
 * One package-private append-only journal is authoritative for the execution. Event publication,
 * route-failure redaction, and thresholded SLF4J emission are separate package-private owners;
 * append always precedes emission. Detached vocabulary/read models and stateless rendering live in
 * {@code journal} and {@code diagnostics}, which do not depend back on execution.
 */
package io.github.jacekkardys.systemproof.engine.execution;
