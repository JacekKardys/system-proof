package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
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

class ScenarioEventTest {
    @Test
    void shouldExposeOnlyAClosedStructurallyImmutableEventHierarchy() {
        assertThat(ScenarioEvent.class.isSealed()).isTrue();
        assertClosedImmutableHierarchy(ScenarioEvent.class, new HashSet<>());
        assertEvidenceSnapshotBoundary();
    }

    @Test
    void shouldNeverRetainThrowableInTheEventOrReadModelSurface() {
        Set<Class<?>> inspected = new HashSet<>();
        assertNoThrowable(ScenarioEvent.class, inspected);
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
            .noneMatch(field -> Throwable.class.isAssignableFrom(field.getType()));
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
        if (valueType == ProofSubjectRef.class) {
            assertThat(valueType.isInterface()).isTrue();
            assertThat(valueType.getDeclaredMethods()).isEmpty();
            return;
        }
        if (valueType == CorrelationKey.class) {
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
}
