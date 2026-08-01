package io.github.jacekkardys.systemproof.junit.internal;

import java.util.Objects;
import java.util.Optional;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Adapts lifecycle callback failures to JUnit primary and suppressed failure semantics. */
final class SystemProofLifecycleFailureAdapter {

    Outcome execute(ExtensionContext context, Runnable operation) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        try {
            operation.run();
            return Outcome.success();
        } catch (RuntimeException | Error failure) {
            val primaryFailure = context.getExecutionException();
            primaryFailure.ifPresent(primary -> suppress(primary, failure));
            return Outcome.failure(failure, primaryFailure.isEmpty());
        }
    }

    static void suppress(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    static final class Outcome {
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

        Optional<Throwable> failure() {
            return Optional.ofNullable(failure);
        }

        void propagateIfPrimary() {
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
