package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;

/** Signals a runtime operation outside the component's running lifecycle. */
public final class ComponentLifecycleException extends IllegalStateException {
    ComponentLifecycleException(
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
