package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.model.component.ComponentId;
import io.github.jacekkardys.systemproof.model.component.ComponentState;
import io.github.jacekkardys.systemproof.model.component.ComponentType;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;
import io.github.jacekkardys.systemproof.model.logging.LogLevel;

class ScenarioJournalTest {
    private static final ComponentId COMPONENT =
        ComponentId.component(ComponentType.of("service"));

    @Test
    void shouldExposeOnlyAClosedStructurallyImmutableEventHierarchy() {
        assertThat(ScenarioEvent.class.isSealed()).isTrue();

        assertClosedImmutableHierarchy(ScenarioEvent.class, new HashSet<>());
        assertEvidenceSnapshotBoundary();
    }

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
        assertThatThrownBy(() -> ConnectionId.of(
            "connection" + System.lineSeparator() + "payload"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid connection id:");
        assertThat(journal.snapshot().isEmpty()).isTrue();
    }

    private static DiagnosticEvent diagnostic(String message) {
        return new DiagnosticEvent(
            DiagnosticEvent.EnvironmentSubject.INSTANCE,
            LogLevel.INFO,
            message
        );
    }

    private static void assertClosedImmutableHierarchy(
        Class<?> type,
        Set<Class<?>> inspected
    ) {
        if (!inspected.add(type)) {
            return;
        }
        if (type.isPrimitive() || type.isEnum() || type == String.class) {
            return;
        }
        if (type.isSealed()) {
            assertThat(type.getPermittedSubclasses())
                .as("%s permitted types", type.getName())
                .isNotEmpty()
                .allSatisfy(permitted -> {
                    assertThat(
                        permitted.isSealed() || permitted.isRecord() || permitted.isEnum()
                    )
                        .as("%s must remain sealed or be an immutable value", permitted.getName())
                        .isTrue();
                    assertClosedImmutableHierarchy(permitted, inspected);
                });
            return;
        }

        assertThat(type.isRecord())
            .as("%s must be an immutable record", type.getName())
            .isTrue();
        for (RecordComponent component : type.getRecordComponents()) {
            assertImmutableType(component.getGenericType(), inspected);
        }
    }

    private static void assertImmutableType(Type type, Set<Class<?>> inspected) {
        if (type instanceof ParameterizedType parameterized) {
            assertThat(parameterized.getRawType())
                .as("%s must use a supported immutable container", type.getTypeName())
                .isEqualTo(Optional.class);
            for (Type argument : parameterized.getActualTypeArguments()) {
                assertImmutableType(argument, inspected);
            }
            return;
        }

        assertThat(type).isInstanceOf(Class.class);
        Class<?> valueType = (Class<?>) type;
        assertThat(valueType.isArray())
            .as("%s must not expose mutable array state", valueType.getName())
            .isFalse();
        assertThat(Collection.class.isAssignableFrom(valueType))
            .as("%s must not expose mutable collection state", valueType.getName())
            .isFalse();
        assertThat(Map.class.isAssignableFrom(valueType))
            .as("%s must not expose mutable map state", valueType.getName())
            .isFalse();
        if (valueType.isPrimitive() || valueType.isEnum() || valueType == String.class) {
            return;
        }
        if (valueType == EvidenceSnapshot.class) {
            assertEvidenceSnapshotBoundary();
            return;
        }
        if (valueType == CorrelationKey.class || valueType == ProofSubjectRef.class) {
            assertOpaqueImmutableValue(valueType);
            return;
        }
        assertClosedImmutableHierarchy(valueType, inspected);
    }

    private static void assertEvidenceSnapshotBoundary() {
        assertThat(Modifier.isFinal(EvidenceSnapshot.class.getModifiers())).isTrue();
        assertClosedImmutableHierarchy(EvidenceSchemaId.class, new HashSet<>());
        assertThat(EvidenceSnapshot.class.getDeclaredFields())
            .allSatisfy(field -> {
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            });
        assertThat(EvidenceSnapshot.class.getMethods())
            .filteredOn(method -> method.getDeclaringClass() == EvidenceSnapshot.class)
            .allSatisfy(method -> {
                Class<?> returnType = method.getReturnType();
                assertThat(returnType.isArray())
                    .as("%s must not expose mutable arrays", method)
                    .isFalse();
                assertThat(Collection.class.isAssignableFrom(returnType))
                    .as("%s must not expose mutable collections", method)
                    .isFalse();
                assertThat(Map.class.isAssignableFrom(returnType))
                    .as("%s must not expose mutable maps", method)
                    .isFalse();
            });
    }

    private static void assertOpaqueImmutableValue(Class<?> type) {
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type.getDeclaredFields())
            .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
            .allSatisfy(field -> {
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            });
        assertThat(type.getMethods())
            .filteredOn(method -> method.getDeclaringClass() == type)
            .allSatisfy(method -> {
                Class<?> returnType = method.getReturnType();
                assertThat(returnType.isArray())
                    .as("%s must not expose mutable arrays", method)
                    .isFalse();
                assertThat(Collection.class.isAssignableFrom(returnType))
                    .as("%s must not expose mutable collections", method)
                    .isFalse();
                assertThat(Map.class.isAssignableFrom(returnType))
                    .as("%s must not expose mutable maps", method)
                    .isFalse();
            });
    }
}
