package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.journal.DiagnosticEvent;
import io.github.jacekkardys.systemproof.journal.EnvironmentLifecycleEvent;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.JournalSequence;
import io.github.jacekkardys.systemproof.journal.ScenarioJournalSnapshot;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;

class ScenarioJournalStorageTest {
    @Test
    void shouldAssignAndInsertOneBasedSequencesAtomically() throws Exception {
        int workers = 8;
        int eventsPerWorker = 100;
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int event = 0; event < eventsPerWorker; event++) {
                        journal.append(diagnostic(workerId + ":" + event));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        List<JournalEntry> entries = journal.snapshot().entries();
        int expectedCount = workers * eventsPerWorker;
        assertThat(JournalSequence.FIRST_VALUE).isEqualTo(1L);
        assertThat(entries).hasSize(expectedCount);
        assertThat(entries)
            .extracting(entry -> entry.journalSequence().value())
            .containsExactlyElementsOf(LongStream.rangeClosed(1, expectedCount).boxed().toList());
        assertThat(entries)
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message().content())
            .doesNotHaveDuplicates();
        assertThat(new HashSet<>(entries)).hasSize(expectedCount);
        assertThat(Comparable.class.isAssignableFrom(JournalSequence.class)).isFalse();
    }

    @Test
    void shouldReturnDetachedImmutableStorageOrderSnapshots() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        JournalEntry first = journal.append(diagnostic("first"));
        ScenarioJournalSnapshot earlier = journal.snapshot();

        journal.append(diagnostic("second"));

        assertThat(earlier.entries()).containsExactly(first);
        assertThat(journal.snapshot().entries())
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message().content())
            .containsExactly("first", "second");
        assertThatThrownBy(() -> earlier.entries().add(first))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRecordOptionalMonotonicDiagnosticTimeWithoutAffectingStorageOrder() {
        AtomicLong clock = new AtomicLong();
        ScenarioJournal journal = new ScenarioJournal(clock::get);

        clock.set(TimeUnit.MILLISECONDS.toNanos(25));
        JournalEntry first = journal.append(
            new EnvironmentLifecycleEvent(EnvironmentState.STARTING)
        );
        JournalEntry second = journal.append(diagnostic("same timestamp"));

        assertThat(first.diagnosticElapsedTime()).contains(Duration.ofMillis(25));
        assertThat(second.diagnosticElapsedTime()).contains(Duration.ofMillis(25));
        assertThat(journal.snapshot().entries()).containsExactly(first, second);

        JournalEntry withoutTime = ScenarioJournal.withoutDiagnosticTime()
            .append(diagnostic("no time"));
        assertThat(withoutTime.diagnosticElapsedTime()).isEmpty();
    }

    @Test
    void shouldRejectBackwardsDiagnosticTimeBeforeInsertion() {
        AtomicLong clock = new AtomicLong(10L);
        ScenarioJournal journal = new ScenarioJournal(clock::get);
        clock.set(9L);

        assertThatThrownBy(() -> journal.append(diagnostic("rejected")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Monotonic diagnostic time moved backwards");
        assertThat(journal.snapshot().isEmpty()).isTrue();
    }

    @Test
    void shouldRejectNullBeforeChangingStorage() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);

        assertThatThrownBy(() -> journal.append(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("event must not be null");
        assertThat(journal.snapshot().isEmpty()).isTrue();
    }

    private static DiagnosticEvent diagnostic(String message) {
        return new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            RedactedDiagnosticText.redact(message, input -> input)
        );
    }
}
