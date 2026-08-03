package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.configuration.ConfigurationValidator.validate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.topology.PortRef;

/**
 * Writes validated construction results into a declarative component instance.
 * Reflection is isolated here because user component classes expose a no-argument declaration
 * constructor; neither the accessor nor the mutable construction state escapes into the model or runtime.
 */
final class ComponentInitializer {
    private static final Field ID = field("id");
    private static final Field CONFIGURATION = field("configuration");
    private static final Field OPERATIONS_TYPE = field("operationsType");
    private static final Field DRIVER = field("driver");
    private static final Field PORTS = field("ports");

    private ComponentInitializer() {}

    static <C extends RuntimeConfig, O> void initialize(AbstractComponent<C, O> component, ComponentId id,
        C configuration, Class<O> operationsType, ComponentDriver<C, O> driver) {
        Objects.requireNonNull(component, "component must not be null");
        if (component.id() != null) {
            throw new IllegalStateException("Component '" + component.id() + "' is already initialized");
        }
        write(ID, component, Objects.requireNonNull(id, "id must not be null"));
        write(CONFIGURATION, component, validate(configuration));
        write(OPERATIONS_TYPE, component, operationsType == Void.class ? null : operationsType);
        write(DRIVER, component, Objects.requireNonNull(driver, "driver must not be null"));
        PortDeclarations.initialize(component);
    }

    static <P extends PortRef> P register(AbstractComponent<?, ?> component, P port) {
        List<PortRef> ports = ports(component);
        if (ports.stream().anyMatch(existing -> existing.name().equals(port.name()))) {
            throw new IllegalArgumentException(
                "Duplicate port name '" + port.name() + "' in component '" + component.id() + "'"
            );
        }
        ports.add(port);
        return port;
    }

    private static Field field(String name) {
        try {
            Field field = AbstractComponent.class.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("Cannot access AbstractComponent." + name);
            }
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void write(Field field, AbstractComponent<?, ?> component, Object value) {
        try {
            field.set(component, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot initialize AbstractComponent." + field.getName(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PortRef> ports(AbstractComponent<?, ?> component) {
        try {
            return (List<PortRef>) PORTS.get(component);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access AbstractComponent.ports", exception);
        }
    }
}
