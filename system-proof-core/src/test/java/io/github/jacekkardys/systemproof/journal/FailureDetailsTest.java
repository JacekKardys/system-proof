package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FailureDetailsTest {
    private static final String SECRET = "throwable-text-canary";

    @Test
    void shouldFreezeOnlyTypeWithoutInspectingThrowableTextOrStackTrace() {
        AtomicInteger textInspections = new AtomicInteger();
        HostileFailure failure = new HostileFailure(textInspections);
        failure.initCause(new IllegalArgumentException("cause-" + SECRET));
        failure.addSuppressed(new IllegalStateException("suppressed-" + SECRET));

        FailureDetails details = FailureDetails.from(failure);

        assertThat(details.failureType()).isEqualTo("HostileFailure");
        assertThat(details.toString()).isEqualTo(
            "FailureDetails[failureType=HostileFailure]"
        );
        assertThat(textInspections).hasValue(0);
    }

    @Test
    void shouldDetachAValueClassificationAtCreationTime() {
        FailureDetails first = FailureDetails.from(new IllegalStateException("first"));
        FailureDetails second = FailureDetails.from(new IllegalStateException("second"));
        FailureDetails different = FailureDetails.from(new IllegalArgumentException("other"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(different);
        assertThat(first.failureType()).isEqualTo("IllegalStateException");
        assertThat(FailureDetails.class.getConstructors()).isEmpty();
        assertThat(FailureDetails.class.getMethods())
            .noneMatch(method -> method.getName().equals("failureClass"));
        assertThat(FailureDetails.class.getDeclaredFields())
            .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
            .singleElement()
            .satisfies(field -> {
                assertThat(field.getType()).isEqualTo(String.class);
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            });
    }

    @Test
    void shouldUseFallbackForAnonymousThrowableAndBoundLongClassification() {
        FailureDetails anonymous = FailureDetails.from(new RuntimeException() {});
        FailureDetails longClassification = FailureDetails.from(
            new FailureClassificationNameThatIsDeliberatelyLongToExerciseTheBoundedNormalizationAtCreationWithoutRetainingItsRuntimeClassObjectBeyondTheFactoryBoundary()
        );

        assertThat(anonymous.failureType()).isEqualTo("Throwable");
        assertThat(longClassification.failureType())
            .hasSize(128)
            .matches("[A-Za-z0-9_$]+");
        assertThat(longClassification.toString())
            .isEqualTo(
                "FailureDetails[failureType=" + longClassification.failureType() + "]"
            );
    }

    private static final class HostileFailure extends RuntimeException {
        private final AtomicInteger textInspections;

        private HostileFailure(AtomicInteger textInspections) {
            super((String) null);
            this.textInspections = textInspections;
        }

        @Override
        public String getMessage() {
            textInspections.incrementAndGet();
            return SECRET;
        }

        @Override
        public String getLocalizedMessage() {
            textInspections.incrementAndGet();
            return SECRET;
        }

        @Override
        public synchronized Throwable getCause() {
            textInspections.incrementAndGet();
            return super.getCause();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            textInspections.incrementAndGet();
            return super.getStackTrace();
        }

        @Override
        public String toString() {
            textInspections.incrementAndGet();
            return SECRET;
        }
    }

    private static final class FailureClassificationNameThatIsDeliberatelyLongToExerciseTheBoundedNormalizationAtCreationWithoutRetainingItsRuntimeClassObjectBeyondTheFactoryBoundary
        extends RuntimeException {}
}
