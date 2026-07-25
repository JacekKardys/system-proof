package io.github.jacekkardys.systemproof.driver;

import io.github.jacekkardys.systemproof.journal.CheckpointEvent;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.journal.DisruptionLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.InteractionMetadata;

/**
 * Component-scoped capability for supported external journal contributions.
 *
 * <p>The framework supplies the observing component identity. This capability cannot append
 * framework lifecycle, framework failure, or free-form diagnostic events and does not expose the
 * mutable journal.
 */
public interface JournalContributions {
    <T> void observeInteraction(
        InteractionMetadata metadata,
        EvidenceCodec<T> codec,
        T evidence
    );

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
