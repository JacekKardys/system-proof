package io.github.jacekkardys.systemproof.journal;

import java.util.Objects;

/**
 * A one-based storage position assigned inside one {@link ScenarioJournal}.
 *
 * <p>This value exists only for uniqueness, snapshot storage order, and deterministic rendering.
 * It is not a timestamp, a distributed sequence, an evidence position, or proof of causality or
 * happens-before. The type deliberately does not implement {@link Comparable}.
 */
public final class JournalSequence {
    public static final long FIRST_VALUE = 1L;

    private final long value;

    JournalSequence(long value) {
        if (value < FIRST_VALUE) {
            throw new IllegalArgumentException(
                "journalSequence must be at least " + FIRST_VALUE
            );
        }
        this.value = value;
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof JournalSequence sequence && value == sequence.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
