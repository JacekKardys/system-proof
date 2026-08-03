package io.github.jacekkardys.systemproof.journal;

/**
 * One core-owned immutable envelope retained in a scenario journal.
 *
 * <p>The hierarchy is closed so that a journal cannot retain arbitrary externally implemented,
 * mutable event objects. Protocol modules contribute typed values through a copy boundary that
 * produces a framework-owned {@link EvidenceSnapshot}; their value and codec are never retained.
 * Public record constructors create detached values but do not provide an append path. Before
 * version 1.0, adding a permitted variant is an explicit compatibility change for callers using
 * exhaustive pattern matching.
 */
public sealed interface ScenarioEvent permits
    EnvironmentLifecycleEvent,
    ComponentLifecycleEvent,
    ConnectionLifecycleEvent,
    FailureEvent,
    DiagnosticEvent,
    InteractionObservationEvent,
    ProofSubjectCreatedEvent,
    ProofSubjectArmedEvent,
    CorrelationCandidateEvent,
    CheckpointEvent,
    DisruptionLifecycleEvent {
}
