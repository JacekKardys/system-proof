package io.github.jacekkardys.systemproof.model.component;

import java.util.ArrayList;
import java.util.List;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.topology.PortRef;

/**
 * Runtime-facing model of a typed logical component initialized before environment execution.
 *
 * @param <C> runtime configuration type
 * @param <O> optional typed operations exposed by the component driver
 */
public abstract class AbstractComponent<C extends RuntimeConfig, O> implements Component {
    private ComponentId id;
    private C configuration;
    private Class<O> operationsType;
    private ComponentDriver<C, O> driver;
    private final List<PortRef> ports = new ArrayList<>();

    /** Constructor used by declarative component materialization. */
    protected AbstractComponent() {}

    /**
     * Low-level constructor for programmatic component models.
     * Supplied values are assumed to have been validated by the caller.
     */
    protected AbstractComponent(ComponentId id, C configuration, Class<O> operationsType, ComponentDriver<C, O> driver) {
        this.id = id;
        this.configuration = configuration;
        this.operationsType = operationsType == Void.class ? null : operationsType;
        this.driver = driver;
    }

    @Override
    public final ComponentId id() {
        return id;
    }

    @Override
    public final ComponentType type() {
        return id.type();
    }

    @Override
    public final C configuration() {
        return configuration;
    }

    @Override
    public final List<PortRef> ports() {
        return List.copyOf(ports);
    }

    public final ComponentDriver<C, O> driver() {
        return driver;
    }

    public final O castOperations(Object operations) {
        if (operationsType == null) {
            throw new IllegalStateException(
                "Component '" + id + "' (type=" + type() + ") declares no runtime operations"
            );
        }
        return operationsType.cast(operations);
    }

}
