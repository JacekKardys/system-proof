package io.github.jacekkardys.systemproof.journal;

import static org.assertj.core.api.Assertions.assertThat;

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
}
