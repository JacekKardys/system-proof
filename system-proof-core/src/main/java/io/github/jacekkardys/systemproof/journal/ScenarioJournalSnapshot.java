package io.github.jacekkardys.systemproof.journal;

import java.util.List;
import java.util.Objects;

/** Detached immutable storage-order snapshot of one scenario journal. */
public final class ScenarioJournalSnapshot {
    private final List<JournalEntry> entries;

    ScenarioJournalSnapshot(List<JournalEntry> entries) {
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
