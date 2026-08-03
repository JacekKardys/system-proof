package io.github.jacekkardys.systemproof.component;

import java.util.ArrayList;
import java.util.List;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.topology.PortRef;

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
    private boolean initialized;
    private boolean portDeclarationsFrozen;

    /** Constructor used by declarative component materialization. */
    protected AbstractComponent() {}

    /**
     * Low-level constructor for programmatic component models.
     * Supplied values and ports remain construction state until {@code EnvironmentTopology.of(...)}
     * validates the complete declaration and freezes its port set. Incomplete values are rejected at
     * that boundary before environment execution.
     */
    protected AbstractComponent(ComponentId id, C configuration, Class<O> operationsType, ComponentDriver<C, O> driver) {
        this.id = id;
        this.configuration = configuration;
        this.operationsType = operationsType;
        this.driver = driver;
        initialized = true;
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
        if (operationsType == Void.class) {
            throw new IllegalStateException(
                "Component '" + id + "' (type=" + type() + ") declares no runtime operations"
            );
        }
        return operationsType.cast(operations);
    }

}
