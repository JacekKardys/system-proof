package io.github.jacekkardys.systemproof.junit.internal.execution;

import java.util.Objects;
import lombok.val;

/** Adapts lifecycle callback failures to JUnit primary and suppressed failure semantics. */
public final class SystemProofLifecycleFailureAdapter {

    public Outcome execute(SystemProofSharedContext context, Runnable operation) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        try {
            operation.run();
            return Outcome.success();
        } catch (RuntimeException | Error failure) {
            context.putLifecycleFailure(failure);
            val primaryFailure = context.executionException();
            primaryFailure.ifPresent(primary -> suppress(primary, failure));
            return Outcome.failure(failure, primaryFailure.isEmpty());
        }
    }

    static void suppress(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    public static final class Outcome {
        private final Throwable failure;
        private final boolean primary;

        private Outcome(Throwable failure, boolean primary) {
            this.failure = failure;
            this.primary = primary;
        }

        private static Outcome success() {
            return new Outcome(null, false);
        }

        private static Outcome failure(Throwable failure, boolean primary) {
            return new Outcome(failure, primary);
        }

        public void propagateIfPrimary() {
            if (!primary) {
                return;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) failure;
        }
    }
}
