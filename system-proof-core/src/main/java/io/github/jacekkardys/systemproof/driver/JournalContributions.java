package io.github.jacekkardys.systemproof.driver;

import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;

/**
 * Component-scoped capability for supported component-owned journal contributions.
 *
 * <p>The framework supplies the component identity. Traffic observations belong to a separate
 * connection-scoped capability. This capability cannot append framework lifecycle, framework
 * failure, free-form diagnostic, or interaction events and does not expose the mutable journal.
 */
public interface JournalContributions {
    void recordCheckpoint(
        CheckpointId checkpointId,
        CheckpointEvent.Kind kind,
        CheckpointEvent.Stage stage
    );

    void recordDisruption(
        DisruptionId disruptionId,
        DisruptionLifecycleEvent.Stage stage
    );
}
