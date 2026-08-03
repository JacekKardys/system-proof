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
 * constructor; neither the accessor nor the mutable construction state escapes into the model or
 * runtime. Initialization, port registration, and topology validation/freeze share this class's
 * monitor so a topology observes and freezes one stable declaration state.
 */
final class ComponentInitializer {
    private static final Field ID = field("id");
    private static final Field CONFIGURATION = field("configuration");
    private static final Field OPERATIONS_TYPE = field("operationsType");
    private static final Field DRIVER = field("driver");
    private static final Field PORTS = field("ports");
    private static final Field INITIALIZED = field("initialized");
    private static final Field PORT_DECLARATIONS_FROZEN = field("portDeclarationsFrozen");

    private ComponentInitializer() {}

    static <C extends RuntimeConfig, O> void initialize(AbstractComponent<C, O> component, ComponentId id,
        C configuration, Class<O> operationsType, ComponentDriver<C, O> driver) {
        synchronized (ComponentInitializer.class) {
            Objects.requireNonNull(component, "component must not be null");
            if (initialized(component) || component.id() != null) {
                throw new IllegalStateException("Component '" + component.id() + "' is already initialized");
            }
            write(ID, component, Objects.requireNonNull(id, "id must not be null"));
            write(CONFIGURATION, component, validate(configuration));
            write(
                OPERATIONS_TYPE,
                component,
                Objects.requireNonNull(operationsType, "operationsType must not be null")
            );
            write(DRIVER, component, Objects.requireNonNull(driver, "driver must not be null"));
            PortDeclarations.initialize(component);
            write(INITIALIZED, component, true);
        }
    }

    static <P extends PortRef> P register(AbstractComponent<?, ?> component, P port) {
        synchronized (ComponentInitializer.class) {
            Objects.requireNonNull(component, "component must not be null");
            Objects.requireNonNull(port, "port must not be null");
            if (portDeclarationsFrozen(component)) {
                throw new IllegalStateException(
                    "Component '" + component.id() + "' port declarations are frozen"
                );
            }
            List<PortRef> ports = ports(component);
            if (ports.stream().anyMatch(existing -> existing.name().equals(port.name()))) {
                throw new IllegalArgumentException(
                    "Duplicate port name '" + port.name() + "' in component '" + component.id() + "'"
                );
            }
            ports.add(port);
            return port;
        }
    }

    static void validateInitialized(AbstractComponent<?, ?> component) {
        requireDeclarationLock();
        if (!initialized(component)) {
            throw new IllegalArgumentException(
                "Component type '" + component.getClass().getName()
                    + "' has not completed declaration initialization"
            );
        }
        if (component.id() == null) {
            throw incomplete(component, "has null ComponentId");
        }
        if (component.configuration() == null) {
            throw incomplete(component, "has null configuration");
        }
        if (operationsType(component) == null) {
            throw incomplete(component, "has null operations type");
        }
        if (component.driver() == null) {
            throw incomplete(component, "has null driver");
        }
    }

    static void freezePortDeclarations(AbstractComponent<?, ?> component) {
        requireDeclarationLock();
        write(PORT_DECLARATIONS_FROZEN, component, true);
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

    private static IllegalArgumentException incomplete(
        AbstractComponent<?, ?> component,
        String reason
    ) {
        String identity = component.id() == null
            ? component.getClass().getName()
            : component.id().toString();
        return new IllegalArgumentException(
            "Component '" + identity + "' " + reason
        );
    }

    private static boolean initialized(AbstractComponent<?, ?> component) {
        return readBoolean(INITIALIZED, component);
    }

    private static boolean portDeclarationsFrozen(AbstractComponent<?, ?> component) {
        return readBoolean(PORT_DECLARATIONS_FROZEN, component);
    }

    private static Class<?> operationsType(AbstractComponent<?, ?> component) {
        return (Class<?>) read(OPERATIONS_TYPE, component);
    }

    private static boolean readBoolean(Field field, AbstractComponent<?, ?> component) {
        return (boolean) read(field, component);
    }

    private static Object read(Field field, AbstractComponent<?, ?> component) {
        try {
            return field.get(component);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                "Cannot inspect AbstractComponent." + field.getName(),
                exception
            );
        }
    }

    private static void requireDeclarationLock() {
        if (!Thread.holdsLock(ComponentInitializer.class)) {
            throw new IllegalStateException("Component declaration lock is not held");
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
