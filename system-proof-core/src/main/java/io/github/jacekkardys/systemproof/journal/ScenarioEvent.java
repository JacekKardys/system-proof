package io.github.jacekkardys.systemproof.journal;

/**
 * One immutable fact retained in a scenario journal.
 *
 * <p>The category interfaces deliberately remain open for later protocol-specific interaction
 * observations, semantic checkpoints, and disruptions without introducing parallel stores.
 */
public sealed interface ScenarioEvent permits
    EnvironmentLifecycleEvent,
    ComponentLifecycleEvent,
    InteractionObservation,
    CheckpointEvent,
    DisruptionEvent,
    FailureEvent,
    DiagnosticEvent {
}
