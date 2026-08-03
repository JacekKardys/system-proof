package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;

/** Constructs stable runtime failures without owning lifecycle decisions. */
final class EnvironmentRuntimeFailures {
    private EnvironmentRuntimeFailures() {}

    static IllegalStateException environmentCannotStart(EnvironmentState state) {
        return new IllegalStateException("Environment cannot start from state " + state);
    }

    static IllegalStateException environmentCannotClose(EnvironmentState state) {
        return new IllegalStateException("Environment cannot close from state " + state);
    }

    static IllegalArgumentException componentOutsideEnvironment(Component component) {
        return new IllegalArgumentException(
            "Component '" + component.id() + "' is outside the environment"
        );
    }

    static ComponentLifecycleException componentNotRunning(
        AbstractComponent<?, ?> component,
        ComponentState actual
    ) {
        return new ComponentLifecycleException(
            component.id(),
            component.type(),
            actual,
            ComponentState.RUNNING
        );
    }

    static IllegalStateException invalidEnvironmentTransition(
        EnvironmentState current,
        EnvironmentState next
    ) {
        return new IllegalStateException(
            "Environment cannot transition from state " + current + " to " + next
        );
    }

    static IllegalStateException invalidComponentTransition(
        Component component,
        ComponentState current,
        ComponentState next
    ) {
        return new IllegalStateException(
            "Component '" + component.id() + "' cannot transition from state "
                + current + " to " + next
        );
    }

    static IllegalStateException componentAlreadyStarted(Component component) {
        return new IllegalStateException(
            "Component '" + component.id() + "' was already recorded as started"
        );
    }

    static Throwable accumulate(Throwable first, Throwable next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        if (first == next || isAlreadySuppressed(first, next)) {
            return first;
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean isAlreadySuppressed(Throwable primary, Throwable candidate) {
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == candidate) {
                return true;
            }
        }
        return false;
    }

    static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Environment cleanup failed", failure);
    }
}
