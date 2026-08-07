package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.control.SemanticHoldRef;
import io.github.jacekkardys.systemproof.control.SemanticPredecessorGuardRef;

class ScenarioEventTest {
    private static final Set<Class<? extends ScenarioEvent>> FRAMEWORK_EVENTS = Set.of(
        EnvironmentLifecycleEvent.class,
        ComponentLifecycleEvent.class,
        ConnectionLifecycleEvent.class,
        FailureEvent.class,
        DiagnosticEvent.class,
        InteractionObservationEvent.class,
        ProofSubjectCreatedEvent.class,
        ProofSubjectArmedEvent.class,
        CorrelationCandidateEvent.class,
        SemanticHoldEvent.class,
        SemanticPredecessorGuardEvent.class,
        CheckpointEvent.class,
        DisruptionLifecycleEvent.class
    );

    @Test
    void shouldKeepTheRootOpenWhileFrameworkEventsRemainStructurallyImmutable() {
        assertThat(ScenarioEvent.class.isSealed()).isFalse();
        assertThat(ScenarioEvent.class.isAssignableFrom(ClientScenarioEvent.class)).isTrue();
        FRAMEWORK_EVENTS.forEach(eventType ->
            assertClosedImmutableHierarchy(eventType, new HashSet<>())
        );
        assertEvidenceSnapshotBoundary();
    }

    @Test
    void shouldInventoryEveryTopLevelFrameworkEventInTheJournalPackage() throws Exception {
        Path packageDirectory = Path.of(
            ScenarioEvent.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).resolve(ScenarioEvent.class.getPackageName().replace('.', '/'));
        Set<Class<? extends ScenarioEvent>> discovered = new HashSet<>();
        try (var classes = Files.list(packageDirectory)) {
            for (Path path : classes
                .filter(candidate -> candidate.getFileName().toString().endsWith(".class"))
                .filter(candidate -> !candidate.getFileName().toString().contains("$"))
                .toList()) {
                String simpleName = path.getFileName().toString().replaceFirst("\\.class$", "");
                Class<?> type = Class.forName(
                    ScenarioEvent.class.getPackageName() + "." + simpleName,
                    false,
                    ScenarioEvent.class.getClassLoader()
                );
                if (type != ScenarioEvent.class
                    && Modifier.isPublic(type.getModifiers())
                    && ScenarioEvent.class.isAssignableFrom(type)) {
                    discovered.add(type.asSubclass(ScenarioEvent.class));
                }
            }
        }

        assertThat(discovered).containsExactlyInAnyOrderElementsOf(FRAMEWORK_EVENTS);
    }

    @Test
    void shouldNeverRetainThrowableOrFailureClassInTheEventOrReadModelSurface() {
        Set<Class<?>> inspected = new HashSet<>();
        FRAMEWORK_EVENTS.forEach(eventType -> assertNoThrowable(eventType, inspected));
        assertThat(JournalEntry.class.getDeclaredFields())
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
        assertThat(ScenarioJournalSnapshot.class.getDeclaredFields())
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
    }

    private static void assertNoThrowable(Class<?> type, Set<Class<?>> inspected) {
        if (!inspected.add(type)) {
            return;
        }
        assertThat(type.getDeclaredFields())
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType())
                || Class.class.isAssignableFrom(field.getType()));
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                assertNoThrowable(permitted, inspected);
            }
        }
    }

    private static void assertClosedImmutableHierarchy(Class<?> type, Set<Class<?>> inspected) {
        if (!inspected.add(type) || type.isPrimitive() || type.isEnum() || type == String.class) {
            return;
        }
        if (type.isSealed()) {
            assertThat(type.getPermittedSubclasses())
                .isNotEmpty()
                .allSatisfy(permitted -> {
                    assertThat(permitted.isSealed() || permitted.isRecord() || permitted.isEnum())
                        .as("%s must remain sealed or be an immutable value", permitted.getName())
                        .isTrue();
                    assertClosedImmutableHierarchy(permitted, inspected);
                });
            return;
        }
        assertThat(type.isRecord()).as("%s must be an immutable record", type.getName()).isTrue();
        for (RecordComponent component : type.getRecordComponents()) {
            assertImmutableType(component.getGenericType(), inspected);
        }
    }

    private static void assertImmutableType(Type type, Set<Class<?>> inspected) {
        if (type instanceof ParameterizedType parameterized) {
            assertThat(parameterized.getRawType()).isEqualTo(Optional.class);
            for (Type argument : parameterized.getActualTypeArguments()) {
                assertImmutableType(argument, inspected);
            }
            return;
        }
        assertThat(type).isInstanceOf(Class.class);
        Class<?> valueType = (Class<?>) type;
        assertThat(valueType.isArray()).isFalse();
        assertThat(Collection.class.isAssignableFrom(valueType)).isFalse();
        assertThat(Map.class.isAssignableFrom(valueType)).isFalse();
        if (valueType.isPrimitive() || valueType.isEnum() || valueType == String.class) {
            return;
        }
        if (valueType == EvidenceSnapshot.class) {
            assertEvidenceSnapshotBoundary();
            return;
        }
        if (valueType == ProofSubjectRef.class
            || valueType == SemanticHoldRef.class
            || valueType == SemanticPredecessorGuardRef.class) {
            assertThat(valueType.isInterface()).isTrue();
            assertThat(valueType.getDeclaredMethods()).isEmpty();
            return;
        }
        if (valueType == CorrelationKey.class) {
            assertOpaqueImmutableValue(valueType);
            return;
        }
        if (valueType == RedactedDiagnosticText.class) {
            assertOpaqueImmutableValue(valueType);
            return;
        }
        if (valueType == FailureDetails.class) {
            assertOpaqueImmutableValue(valueType);
            return;
        }
        assertClosedImmutableHierarchy(valueType, inspected);
    }

    private static void assertEvidenceSnapshotBoundary() {
        assertThat(Modifier.isFinal(EvidenceSnapshot.class.getModifiers())).isTrue();
        assertClosedImmutableHierarchy(EvidenceSchemaId.class, new HashSet<>());
        assertOpaqueImmutableValue(EvidenceSnapshot.class);
    }

    private static void assertOpaqueImmutableValue(Class<?> type) {
        assertThat(type.getDeclaredFields())
            .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
            .allSatisfy(field -> {
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            });
        assertThat(type.getMethods())
            .filteredOn(method -> method.getDeclaringClass() == type)
            .allSatisfy(method -> {
                assertThat(method.getReturnType().isArray()).isFalse();
                assertThat(Collection.class.isAssignableFrom(method.getReturnType())).isFalse();
                assertThat(Map.class.isAssignableFrom(method.getReturnType())).isFalse();
            });
    }

    private record ClientScenarioEvent(String value) implements ScenarioEvent {}
}
