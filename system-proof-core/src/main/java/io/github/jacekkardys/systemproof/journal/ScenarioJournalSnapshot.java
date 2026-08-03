package io.github.jacekkardys.systemproof.journal;

import java.util.List;
import java.util.Objects;

/**
 * Detached immutable storage-order snapshot of one scenario journal.
 *
 * <p>The list is unmodifiable and every retained event belongs to the closed structurally
 * immutable {@link ScenarioEvent} hierarchy. Externally typed values are represented only by
 * detached framework-owned {@link EvidenceSnapshot} instances.
 */
public final class ScenarioJournalSnapshot {
    private final List<JournalEntry> entries;

    /**
     * Creates a detached immutable read-model snapshot from the supplied entries.
     *
     * <p>Constructing a snapshot does not create or mutate an environment-owned history.
     */
    public ScenarioJournalSnapshot(List<JournalEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("entries must not contain null");
        }
        this.entries = List.copyOf(entries);
    }

    public List<JournalEntry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
