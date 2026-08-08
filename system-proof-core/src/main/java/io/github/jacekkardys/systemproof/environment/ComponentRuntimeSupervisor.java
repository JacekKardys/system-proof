package io.github.jacekkardys.systemproof.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;

/** Owns component execution state, runtime attachment, operations, rollback, and cleanup. */
final class ComponentRuntimeSupervisor {
    private final ComponentExecutionPlan plan;
    private final RuntimeBindings bindings;
    private final RuntimeDiagnostics diagnostics;
    private final EnvironmentEventPublisher events;
    private final Map<Component, ComponentExecution<?, ?>> executions =
        new IdentityHashMap<>();
    private final DriverServices driverServices;
    private int nextStartIndex;

    ComponentRuntimeSupervisor(
        ComponentExecutionPlan plan,
        RuntimeBindings bindings,
        RuntimeDiagnostics diagnostics,
        EnvironmentEventPublisher events
    ) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.diagnostics = Objects.requireNonNull(
            diagnostics,
            "diagnostics must not be null"
        );
        this.events = Objects.requireNonNull(events, "events must not be null");
        plan.components().forEach(this::register);
        driverServices = new DriverServices(
            bindings,
            this::contains,
            this::state,
            diagnostics,
            events
        );
    }

    boolean startNext() {
        if (nextStartIndex == plan.startOrder().size()) {
            return false;
        }
        start(plan.startOrder().get(nextStartIndex));
        nextStartIndex++;
        return true;
    }

    Throwable stopStartedComponents() {
        Throwable firstFailure = null;
        List<AbstractComponent<?, ?>> reverse = new ArrayList<>(plan.startOrder());
        Collections.reverse(reverse);
        for (AbstractComponent<?, ?> component : reverse) {
            ComponentExecution<?, ?> execution = requireExecution(component);
            if (stateOf(execution) != ComponentState.RUNNING) {
                continue;
            }
            Throwable componentFailure;
            try {
                componentFailure = execution.stop();
            } catch (RuntimeException | Error failure) {
                componentFailure = failure;
            }
            firstFailure = EnvironmentRuntimeFailures.accumulate(
                firstFailure,
                componentFailure
            );
        }
        return firstFailure;
    }

    Throwable closeSharedResources() {
        return driverServices.closeSharedResources();
    }

    synchronized boolean contains(Component component) {
        return executions.containsKey(component);
    }

    synchronized ComponentState state(Component component) {
        return requireExecution(component).state;
    }

    synchronized <C extends RuntimeConfig, O> O operations(
        AbstractComponent<C, O> component
    ) {
        return execution(component).operations();
    }

    List<AbstractComponent<?, ?>> components() {
        return plan.components();
    }

    private <C extends RuntimeConfig, O> void register(AbstractComponent<C, O> component) {
        executions.put(component, new ComponentExecution<>(component));
    }

    private <C extends RuntimeConfig, O> void start(AbstractComponent<C, O> component) {
        execution(component).start();
    }

    private synchronized ComponentState stateOf(ComponentExecution<?, ?> execution) {
        return execution.state;
    }

    private synchronized void beginStart(ComponentExecution<?, ?> execution) {
        transition(execution, ComponentState.DECLARED, ComponentState.STARTING);
    }

    private synchronized <O> void completeStart(
        ComponentExecution<?, O> execution,
        ComponentRuntime<O> runtime
    ) {
        requireState(execution, ComponentState.STARTING, ComponentState.RUNNING);
        if (execution.runtime != null) {
            throw EnvironmentRuntimeFailures.componentAlreadyStarted(execution.component);
        }
        execution.runtime = runtime;
        setState(execution, ComponentState.RUNNING);
    }

    private synchronized void failStart(ComponentExecution<?, ?> execution) {
        transition(execution, ComponentState.STARTING, ComponentState.FAILED);
    }

    private synchronized <O> ComponentRuntime<O> beginStop(
        ComponentExecution<?, O> execution
    ) {
        transition(execution, ComponentState.RUNNING, ComponentState.STOPPING);
        return Objects.requireNonNull(
            execution.runtime,
            "Running component '" + execution.component.id() + "' has no runtime"
        );
    }

    private synchronized void completeStop(
        ComponentExecution<?, ?> execution,
        ComponentState terminalState
    ) {
        requireState(execution, ComponentState.STOPPING, terminalState);
        execution.runtime = null;
        setState(execution, terminalState);
    }

    private void transition(
        ComponentExecution<?, ?> execution,
        ComponentState expected,
        ComponentState next
    ) {
        requireState(execution, expected, next);
        setState(execution, next);
    }

    private void requireState(
        ComponentExecution<?, ?> execution,
        ComponentState expected,
        ComponentState next
    ) {
        if (execution.state != expected) {
            throw EnvironmentRuntimeFailures.invalidComponentTransition(
                execution.component,
                execution.state,
                next
            );
        }
    }

    private void setState(ComponentExecution<?, ?> execution, ComponentState next) {
        execution.state = next;
        events.componentLifecycle(execution.component, next);
    }

    private ComponentExecution<?, ?> requireExecution(Component component) {
        ComponentExecution<?, ?> execution = executions.get(component);
        if (execution == null) {
            throw EnvironmentRuntimeFailures.componentOutsideEnvironment(component);
        }
        return execution;
    }

    @SuppressWarnings("unchecked")
    private <C extends RuntimeConfig, O> ComponentExecution<C, O> execution(
        AbstractComponent<C, O> component
    ) {
        return (ComponentExecution<C, O>) requireExecution(component);
    }

    private final class ComponentExecution<C extends RuntimeConfig, O> {
        private final AbstractComponent<C, O> component;
        private ComponentState state = ComponentState.DECLARED;
        private ComponentRuntime<O> runtime;

        private ComponentExecution(AbstractComponent<C, O> component) {
            this.component = component;
        }

        private void start() {
            beginStart(this);
            ComponentRuntime<O> startedRuntime = null;
            try {
                startedRuntime = Objects.requireNonNull(
                    component.driver().start(
                        component,
                        driverServices.contextFor(component)
                    ),
                    "Driver for component '" + component.id() + "' returned null runtime"
                );
                bindings.attach(component, startedRuntime);
                diagnostics.add(component, startedRuntime.diagnostics());
                completeStart(this, startedRuntime);
            } catch (RuntimeException | Error failure) {
                handleStartupFailure(failure, startedRuntime);
                throw failure;
            }
        }

        private void handleStartupFailure(
            Throwable failure,
            ComponentRuntime<O> startedRuntime
        ) {
            try {
                bindings.providerStartFailure(component, failure);
            } catch (RuntimeException | Error attachmentFailure) {
                EnvironmentRuntimeFailures.accumulate(failure, attachmentFailure);
            }
            failStart(this);
            events.componentStartupFailure(component, failure);
            if (startedRuntime == null) {
                return;
            }
            try {
                startedRuntime.close();
            } catch (Exception | Error cleanupFailure) {
                events.componentCleanupFailure(component, cleanupFailure);
                EnvironmentRuntimeFailures.accumulate(failure, cleanupFailure);
            }
        }

        private Throwable stop() {
            ComponentRuntime<O> startedRuntime = beginStop(this);
            Throwable componentFailure;
            try {
                componentFailure = bindings.beginDetach(component);
            } catch (RuntimeException | Error failure) {
                componentFailure = failure;
            }

            Throwable runtimeFailure = null;
            try {
                startedRuntime.close();
            } catch (Exception | Error failure) {
                runtimeFailure = failure;
                componentFailure = EnvironmentRuntimeFailures.accumulate(
                    componentFailure,
                    failure
                );
            }

            try {
                if (runtimeFailure == null) {
                    bindings.completeDetach(component);
                } else {
                    bindings.failDetach(component, runtimeFailure);
                }
            } catch (RuntimeException | Error attachmentFailure) {
                componentFailure = EnvironmentRuntimeFailures.accumulate(
                    componentFailure,
                    attachmentFailure
                );
            }

            ComponentState terminalState = componentFailure == null
                ? ComponentState.STOPPED
                : ComponentState.FAILED;
            completeStop(this, terminalState);
            if (componentFailure != null) {
                events.componentCleanupFailure(component, componentFailure);
            }
            return componentFailure;
        }

        private O operations() {
            if (state != ComponentState.RUNNING) {
                throw EnvironmentRuntimeFailures.componentNotRunning(component, state);
            }
            ComponentRuntime<O> current = Objects.requireNonNull(
                runtime,
                "Running component '" + component.id() + "' has no runtime"
            );
            Object operations = current.operations();
            if (operations == null) {
                throw new IllegalStateException(
                    "Component '" + component.id() + "' (type=" + component.type()
                        + ") has no runtime operations"
                );
            }
            return component.castOperations(operations);
        }
    }
}
