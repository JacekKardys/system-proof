package io.github.jacekkardys.systemproof.journal;

/**
 * Category for immutable interaction evidence observed at a protocol boundary.
 *
 * <p>Protocol-specific fields and stream-local evidence positions belong to later adapters.
 */
public non-sealed interface InteractionObservation extends ScenarioEvent {
}
