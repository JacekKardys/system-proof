/**
 * Defines immutable scenario-event contracts and detached journal read models.
 *
 * <p>The package exposes no mutable store and no generic append or publication capability.
 * Public event constructors create detached values; only the package-private environment
 * execution journal can make a value part of an authoritative runtime history. The root event
 * interface is open so core can add framework-owned immutable facts without breaking exhaustive
 * client switches; openness does not create an extension or publication SPI.
 * Arbitrary driver text can enter a diagnostic event only through bounded
 * {@link io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText}; failure events retain
 * only type metadata.
 *
 * <p>{@link io.github.jacekkardys.systemproof.journal.JournalSequence} represents one-based local
 * storage order only. Neither it nor diagnostic elapsed time establishes causality, wall-clock
 * order, or distributed order.
 */
package io.github.jacekkardys.systemproof.journal;
