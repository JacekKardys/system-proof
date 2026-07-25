package io.github.jacekkardys.systemproof.journal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The single append-only structured history for one scenario runtime.
 *
 * <p>Sequence assignment and insertion share one synchronization boundary. Snapshots are detached
 * immutable copies in storage order.
 */
public final class ScenarioJournal {
    private final List<JournalEntry> entries = new ArrayList<>();
    private final LongSupplier nanoTime;
    private final long startedAt;
    private final boolean recordsDiagnosticTime;

    /** Creates a journal with diagnostic elapsed time from {@link System#nanoTime()}. */
    public ScenarioJournal() {
        this(System::nanoTime, true);
    }

    /**
     * Creates a journal with an injectable monotonic nanosecond source.
     *
     * <p>The source is for deterministic diagnostics only, never causal inference.
     */
    public ScenarioJournal(LongSupplier nanoTime) {
        this(nanoTime, true);
    }

    /** Creates a journal whose entries intentionally omit diagnostic elapsed time. */
    public static ScenarioJournal withoutDiagnosticTime() {
        return new ScenarioJournal(() -> 0L, false);
    }

    private ScenarioJournal(LongSupplier nanoTime, boolean recordsDiagnosticTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        this.recordsDiagnosticTime = recordsDiagnosticTime;
        startedAt = recordsDiagnosticTime ? nanoTime.getAsLong() : 0L;
    }

    /**
     * Appends one event and returns the exact immutable stored entry.
     *
     * <p>Callers supply only the event. The journal assigns the local storage sequence.
     */
    public synchronized JournalEntry append(ScenarioEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Optional<Duration> elapsedTime = diagnosticElapsedTime();
        JournalSequence sequence = new JournalSequence(
            JournalSequence.FIRST_VALUE + entries.size()
        );
        JournalEntry entry = new JournalEntry(sequence, elapsedTime, event);
        entries.add(entry);
        return entry;
    }

    public synchronized ScenarioJournalSnapshot snapshot() {
        return new ScenarioJournalSnapshot(entries);
    }

    private Optional<Duration> diagnosticElapsedTime() {
        if (!recordsDiagnosticTime) {
            return Optional.empty();
        }
        long elapsedNanos = nanoTime.getAsLong() - startedAt;
        if (elapsedNanos < 0) {
            throw new IllegalStateException("Monotonic diagnostic time moved backwards");
        }
        return Optional.of(Duration.ofNanos(elapsedNanos));
    }
}
