package io.github.jacekkardys.systemproof.journal;

/**
 * One immutable fact retained in a scenario journal.
 *
 * <p>The interface is deliberately open so framework releases can add immutable control and proof
 * facts without breaking exhaustive client switches. Only environment-owned publishers can append
 * events; implementing this interface does not provide an injection or publication path. Protocol
 * modules contribute typed values through a copy boundary that produces a framework-owned
 * {@link EvidenceSnapshot}; their value and codec are never retained.
 */
public interface ScenarioEvent {}
