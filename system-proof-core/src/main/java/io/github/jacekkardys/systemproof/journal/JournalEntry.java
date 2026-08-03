package io.github.jacekkardys.systemproof.journal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable envelope for one event retained in a scenario journal. */
public final class JournalEntry {
    private final JournalSequence journalSequence;
    private final Optional<Duration> diagnosticElapsedTime;
    private final ScenarioEvent event;

    /**
     * Creates a detached immutable read-model entry.
     *
     * <p>Only entries obtained from an environment snapshot are facts stored by that execution;
     * constructing this value does not publish it.
     */
    public JournalEntry(
        JournalSequence journalSequence,
        Optional<Duration> diagnosticElapsedTime,
        ScenarioEvent event
    ) {
        this.journalSequence = Objects.requireNonNull(
            journalSequence,
            "journalSequence must not be null"
        );
        this.diagnosticElapsedTime = Objects.requireNonNull(
            diagnosticElapsedTime,
            "diagnosticElapsedTime must not be null"
        );
        this.event = Objects.requireNonNull(event, "event must not be null");
    }

    public JournalSequence journalSequence() {
        return journalSequence;
    }

    /**
     * Optional monotonic elapsed time useful only for diagnostics.
     *
     * <p>It must never be used to infer causality or ordering between external events.
     */
    public Optional<Duration> diagnosticElapsedTime() {
        return diagnosticElapsedTime;
    }

    public ScenarioEvent event() {
        return event;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JournalEntry entry)) {
            return false;
        }
        return journalSequence.equals(entry.journalSequence)
            && diagnosticElapsedTime.equals(entry.diagnosticElapsedTime)
            && event.equals(entry.event);
    }

    @Override
    public int hashCode() {
        return Objects.hash(journalSequence, diagnosticElapsedTime, event);
    }

    @Override
    public String toString() {
        return "JournalEntry[journalSequence=" + journalSequence
            + ", diagnosticElapsedTime=" + diagnosticElapsedTime
            + ", event=" + event + "]";
    }
}
