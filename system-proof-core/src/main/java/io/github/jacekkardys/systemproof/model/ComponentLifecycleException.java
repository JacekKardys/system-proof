package io.github.jacekkardys.systemproof.model;

/** Signals a runtime operation outside the component's running lifecycle. */
public final class ComponentLifecycleException extends IllegalStateException {
    public ComponentLifecycleException(
        ComponentId componentId,
        ComponentType componentType,
        ComponentState actual,
        ComponentState expected
    ) {
        super(
            "Component '" + componentId + "' (type=" + componentType + ") cannot perform runtime operation"
                + " in state " + actual + "; expected " + expected
        );
    }
}
