package io.github.jacekkardys.systemproof.journal;

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
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.EnvironmentState;
import io.github.jacekkardys.systemproof.model.LogLevel;

class ScenarioJournalTest {
    private static final ComponentId COMPONENT =
        ComponentId.component(ComponentType.of("service"));

    @Test
    void shouldAssignOneBasedStorageSequencesAndReturnTheStoredTypedEntries() {
        AtomicLong clock = new AtomicLong();
        ScenarioJournal journal = new ScenarioJournal(clock::get);
        EnvironmentLifecycleEvent starting =
            new EnvironmentLifecycleEvent(EnvironmentState.STARTING);

        clock.set(TimeUnit.MILLISECONDS.toNanos(25));
        JournalEntry first = journal.append(starting);
        JournalEntry second = journal.append(
            new ComponentLifecycleEvent(COMPONENT, ComponentState.STARTING)
        );

        assertThat(JournalSequence.FIRST_VALUE).isEqualTo(1L);
        assertThat(first.event()).isSameAs(starting);
        assertThat(first.journalSequence().value()).isEqualTo(1L);
        assertThat(first.diagnosticElapsedTime()).contains(Duration.ofMillis(25));
        assertThat(second.journalSequence().value()).isEqualTo(2L);
        assertThat(journal.snapshot().entries()).containsExactly(first, second);
        assertThat(Comparable.class.isAssignableFrom(JournalSequence.class)).isFalse();
    }

    @Test
    void shouldKeepStorageOrderStableWhenDiagnosticTimesAreEqual() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);

        journal.append(diagnostic("first"));
        journal.append(diagnostic("second"));

        assertThat(journal.snapshot().entries())
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message())
            .containsExactly("first", "second");
        assertThat(journal.snapshot().entries())
            .extracting(JournalEntry::diagnosticElapsedTime)
            .containsOnly(java.util.Optional.of(Duration.ZERO));
    }

    @Test
    void shouldRetainEveryConcurrentAppendExactlyOnceInStorageSequenceOrder() throws Exception {
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
        assertThat(entries).hasSize(expectedCount);
        assertThat(entries)
            .extracting(entry -> entry.journalSequence().value())
            .containsExactlyElementsOf(
                LongStream.rangeClosed(1, expectedCount).boxed().toList()
            );
        assertThat(entries)
            .extracting(entry -> ((DiagnosticEvent) entry.event()).message())
            .hasSize(expectedCount)
            .doesNotHaveDuplicates();
        assertThat(new HashSet<>(entries)).hasSize(expectedCount);
    }

    @Test
    void shouldReturnDetachedImmutableSnapshots() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        JournalEntry first = journal.append(diagnostic("first"));
        ScenarioJournalSnapshot earlier = journal.snapshot();

        journal.append(diagnostic("second"));

        assertThat(earlier.entries()).containsExactly(first);
        assertThat(journal.snapshot().entries()).hasSize(2);
        assertThatThrownBy(() -> earlier.entries().add(first))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldAllowDiagnosticElapsedTimeToBeAbsent() {
        ScenarioJournal journal = ScenarioJournal.withoutDiagnosticTime();

        JournalEntry entry = journal.append(
            new EnvironmentLifecycleEvent(EnvironmentState.STARTING)
        );

        assertThat(entry.diagnosticElapsedTime()).isEmpty();
    }

    @Test
    void shouldRejectInvalidEventsBeforeChangingStorage() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);

        assertThatThrownBy(() -> journal.append(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("event must not be null");
        assertThatThrownBy(() -> new EnvironmentLifecycleEvent(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentLifecycleEvent(null, ComponentState.STARTING))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            " "
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("message must not be blank");
        assertThatThrownBy(() -> new FailureEvent.DriverResourceCleanup(
            " ",
            FailureDetails.from(new IllegalStateException("failed"))
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("resourceName must not be blank");
        assertThat(journal.snapshot().isEmpty()).isTrue();
    }

    private static DiagnosticEvent diagnostic(String message) {
        return new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            message
        );
    }
}
