package io.github.jacekkardys.systemproof.journal;

/**
 * One framework-owned immutable fact retained in a scenario journal.
 *
 * <p>The hierarchy is closed so that a journal cannot retain arbitrary externally implemented,
 * mutable event objects. Future evidence requirements may deliberately extend this sealed model
 * with concrete immutable value types without introducing a parallel history.
 */
public sealed interface ScenarioEvent permits
    EnvironmentLifecycleEvent,
    ComponentLifecycleEvent,
    FailureEvent,
    DiagnosticEvent {
}
