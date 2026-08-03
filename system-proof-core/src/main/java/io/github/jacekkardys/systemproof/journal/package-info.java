/**
 * Defines the closed immutable scenario-event vocabulary and detached journal read models.
 *
 * <p>The package exposes no mutable store and no generic append or publication capability.
 * Public event constructors create detached values; only the package-private environment
 * execution journal can make a value part of an authoritative runtime history. The sealed
 * hierarchy is inspectable and core-controlled. Before 1.0, adding a permitted event is an
 * explicit compatibility change for exhaustive pattern matching.
 *
 * <p>{@link io.github.jacekkardys.systemproof.journal.JournalSequence} represents one-based local
 * storage order only. Neither it nor diagnostic elapsed time establishes causality, wall-clock
 * order, or distributed order.
 */
package io.github.jacekkardys.systemproof.journal;
