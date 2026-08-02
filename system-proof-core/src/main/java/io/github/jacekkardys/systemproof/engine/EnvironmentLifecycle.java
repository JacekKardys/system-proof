package io.github.jacekkardys.systemproof.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.component.ComponentState;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentState;

/** Mutable lifecycle state for one environment execution. */
final class EnvironmentLifecycle {
    private final Map<Component, ComponentState> componentStates = new IdentityHashMap<>();
    private final List<AbstractComponent<?, ?>> startedComponents = new ArrayList<>();
    private final EnvironmentEventLog eventLog;
    private EnvironmentState state = EnvironmentState.DECLARED;

    EnvironmentLifecycle(
        List<AbstractComponent<?, ?>> components,
        EnvironmentEventLog eventLog
    ) {
        Objects.requireNonNull(components, "components must not be null")
            .forEach(component -> componentStates.put(component, ComponentState.DECLARED));
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");
    }

    EnvironmentState state() {
        return state;
    }

    ComponentState componentState(Component component) {
        ComponentState componentState = componentStates.get(component);
        if (componentState == null) {
            throw EnvironmentRuntimeFailures.componentOutsideEnvironment(component);
        }
        return componentState;
    }

    boolean contains(Component component) {
        return componentStates.containsKey(component);
    }

    void beginStart() {
        if (state != EnvironmentState.DECLARED) {
            throw EnvironmentRuntimeFailures.environmentCannotStart(state);
        }
        transitionEnvironment(EnvironmentState.STARTING);
    }

    void markReady() {
        requireEnvironmentState(EnvironmentState.STARTING, EnvironmentState.RUNNING);
        transitionEnvironment(EnvironmentState.RUNNING);
    }

    void markStartFailed() {
        requireEnvironmentState(EnvironmentState.STARTING, EnvironmentState.FAILED);
        transitionEnvironment(EnvironmentState.FAILED);
    }

    CloseAction beginClose() {
        return switch (state) {
            case STOPPED -> CloseAction.ALREADY_STOPPED;
            case DECLARED -> CloseAction.STOP_DECLARED;
            case RUNNING -> {
                transitionEnvironment(EnvironmentState.STOPPING);
                yield CloseAction.CLEAN_UP_RUNNING;
            }
            default -> throw EnvironmentRuntimeFailures.environmentCannotClose(state);
        };
    }

    void markCleanupFailed() {
        requireEnvironmentState(EnvironmentState.STOPPING, EnvironmentState.FAILED);
        transitionEnvironment(EnvironmentState.FAILED);
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
        transitionEnvironment(EnvironmentState.STOPPED);
    }

    void beginComponentStart(AbstractComponent<?, ?> component) {
        transitionComponent(
            component,
            ComponentState.DECLARED,
            ComponentState.STARTING
        );
    }

    void componentStarted(AbstractComponent<?, ?> component) {
        requireComponentState(component, ComponentState.STARTING, ComponentState.RUNNING);
        if (startedComponents.stream().anyMatch(started -> started == component)) {
            throw EnvironmentRuntimeFailures.componentAlreadyStarted(component);
        }
        startedComponents.add(component);
        setComponentState(component, ComponentState.RUNNING);
    }

    void componentStartFailed(AbstractComponent<?, ?> component) {
        transitionComponent(
            component,
            ComponentState.STARTING,
            ComponentState.FAILED
        );
    }

    List<AbstractComponent<?, ?>> componentsToStop() {
        List<AbstractComponent<?, ?>> reverse = new ArrayList<>(startedComponents);
        Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    void beginComponentStop(AbstractComponent<?, ?> component) {
        transitionComponent(
            component,
            ComponentState.RUNNING,
            ComponentState.STOPPING
        );
    }

    void componentStopped(AbstractComponent<?, ?> component) {
        completeComponentStop(component, ComponentState.STOPPED);
    }

    void componentCleanupFailed(AbstractComponent<?, ?> component) {
        completeComponentStop(component, ComponentState.FAILED);
    }

    private void completeComponentStop(
        AbstractComponent<?, ?> component,
        ComponentState terminalState
    ) {
        requireComponentState(component, ComponentState.STOPPING, terminalState);
        removeStartedComponent(component);
        setComponentState(component, terminalState);
    }

    private void removeStartedComponent(AbstractComponent<?, ?> component) {
        for (int index = 0; index < startedComponents.size(); index++) {
            if (startedComponents.get(index) == component) {
                startedComponents.remove(index);
                return;
            }
        }
        throw EnvironmentRuntimeFailures.componentWasNotStarted(component);
    }

    private void requireEnvironmentState(
        EnvironmentState expected,
        EnvironmentState next
    ) {
        if (state != expected) {
            throw EnvironmentRuntimeFailures.invalidEnvironmentTransition(state, next);
        }
    }

    private void transitionEnvironment(EnvironmentState next) {
        state = next;
        eventLog.environmentLifecycle(next);
    }

    private void transitionComponent(
        Component component,
        ComponentState expected,
        ComponentState next
    ) {
        requireComponentState(component, expected, next);
        setComponentState(component, next);
    }

    private void requireComponentState(
        Component component,
        ComponentState expected,
        ComponentState next
    ) {
        ComponentState current = componentState(component);
        if (current != expected) {
            throw EnvironmentRuntimeFailures.invalidComponentTransition(
                component,
                current,
                next
            );
        }
    }

    private void setComponentState(Component component, ComponentState next) {
        componentStates.put(component, next);
        eventLog.componentLifecycle(component, next);
    }

    enum CloseAction {
        ALREADY_STOPPED,
        STOP_DECLARED,
        CLEAN_UP_RUNNING
    }
}
