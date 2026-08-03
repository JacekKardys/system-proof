package io.github.jacekkardys.systemproof.journal;

import java.util.List;
import java.util.Objects;

/**
 * Detached immutable storage-order snapshot of one scenario journal.
 *
 * <p>Authoritative snapshots returned by {@code Environment} contain framework-owned immutable
 * event implementations copied from that environment's history. Snapshots constructed directly
 * by callers are detached values; supplied event implementations must obey the immutable
 * {@link ScenarioEvent} contract. The entry list is unmodifiable in either case. Framework-owned
 * interaction events represent externally typed values only through detached
 * {@link EvidenceSnapshot} instances.
 */
public final class ScenarioJournalSnapshot {
    private final List<JournalEntry> entries;

    /**
     * Creates a detached immutable read-model snapshot from the supplied entries.
     *
     * <p>Constructing a snapshot does not create or mutate an environment-owned history. Callers
     * are responsible for ensuring that supplied {@link ScenarioEvent} implementations are
     * immutable.
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
