package io.github.jacekkardys.systemproof.engine.execution;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;

/** Mutable environment-level lifecycle state for one execution. */
final class EnvironmentLifecycle {
    private final EnvironmentEventPublisher events;
    private EnvironmentState state = EnvironmentState.DECLARED;

    EnvironmentLifecycle(EnvironmentEventPublisher events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    EnvironmentState state() {
        return state;
    }

    void beginStart() {
        if (state != EnvironmentState.DECLARED) {
            throw EnvironmentRuntimeFailures.environmentCannotStart(state);
        }
        transition(EnvironmentState.STARTING);
    }

    void markReady() {
        requireState(EnvironmentState.STARTING, EnvironmentState.RUNNING);
        transition(EnvironmentState.RUNNING);
    }

    void markStartFailed() {
        requireState(EnvironmentState.STARTING, EnvironmentState.FAILED);
        transition(EnvironmentState.FAILED);
    }

    CloseAction beginClose() {
        return switch (state) {
            case STOPPED -> CloseAction.ALREADY_STOPPED;
            case DECLARED -> CloseAction.STOP_DECLARED;
            case RUNNING -> {
                transition(EnvironmentState.STOPPING);
                yield CloseAction.CLEAN_UP_RUNNING;
            }
            default -> throw EnvironmentRuntimeFailures.environmentCannotClose(state);
        };
    }

    void markCleanupFailed() {
        requireState(EnvironmentState.STOPPING, EnvironmentState.FAILED);
        transition(EnvironmentState.FAILED);
    }

    void markStopped() {
        if (state != EnvironmentState.DECLARED
            && state != EnvironmentState.STOPPING
            && state != EnvironmentState.FAILED) {
            throw EnvironmentRuntimeFailures.invalidEnvironmentTransition(
                state,
                EnvironmentState.STOPPED
            );
        }
        transition(EnvironmentState.STOPPED);
    }

    private void requireState(EnvironmentState expected, EnvironmentState next) {
        if (state != expected) {
            throw EnvironmentRuntimeFailures.invalidEnvironmentTransition(state, next);
        }
    }

    private void transition(EnvironmentState next) {
        state = next;
        events.environmentLifecycle(next);
    }

    enum CloseAction {
        ALREADY_STOPPED,
        STOP_DECLARED,
        CLEAN_UP_RUNNING
    }
}
