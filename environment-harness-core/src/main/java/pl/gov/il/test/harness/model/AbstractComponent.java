package pl.gov.il.test.harness.model;

import static pl.gov.il.test.harness.configuration.ConfigurationValidator.validate;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pl.gov.il.test.harness.driver.ComponentDriver;

/** Shared immutable declaration of a typed logical component. */
public abstract class AbstractComponent<C extends RuntimeConfig, O> implements Component {
    private ComponentId id;
    private C configuration;
    private Class<O> operationsType;
    private ComponentDriver<C, O> driver;
    private final List<PortRef> ports = new ArrayList<>();

    protected AbstractComponent() {}

    protected AbstractComponent(
        ComponentId id,
        C configuration,
        Class<O> operationsType,
        ComponentDriver<C, O> driver
    ) {
        initialize(id, configuration, operationsType, driver, false);
    }

    public static <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T component(
        Class<T> componentClass,
        String qualifier,
        C configuration,
        ComponentDriver<C, O> driver
    ) {
        T component = instantiate(componentClass);
        AbstractComponent<C, O> declaration = component;
        declaration.initialize(
            ComponentId.component(declaration.componentType(), qualifier),
            configuration,
            operationsType(componentClass),
            driver,
            true
        );
        return component;
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

    protected abstract ComponentType componentType();

    protected final <T> RequiredPort<T> requires(
        String name,
        Contract<T> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol
    ) {
        return register(new RequiredPort<>(this, name, contract, interaction, protocol, false));
    }

    /**
     * Declares that the provider connected to this communication port must be ready before this
     * component starts.
     */
    protected final <T> RequiredPort<T> requiresAtStartup(
        String name,
        Contract<T> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol
    ) {
        return register(new RequiredPort<>(this, name, contract, interaction, protocol, true));
    }

    protected final <T> ProvidedPort<T> provides(
        String name,
        Contract<T> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol
    ) {
        return register(new ProvidedPort<>(this, name, contract, interaction, protocol));
    }

    final <P extends PortRef> P register(P port) {
        if (ports.stream().anyMatch(existing -> existing.name().equals(port.name()))) {
            throw new IllegalArgumentException(
                "Duplicate port name '" + port.name() + "' in component '" + id + "'"
            );
        }
        ports.add(port);
        return port;
    }

    private void initialize(
        ComponentId id,
        C configuration,
        Class<O> operationsType,
        ComponentDriver<C, O> driver,
        boolean materializeDeclaredPorts
    ) {
        if (this.id != null) {
            throw new IllegalStateException(
                "Component '" + this.id + "' is already initialized"
            );
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.configuration = validate(configuration);
        this.operationsType = operationsType;
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        if (materializeDeclaredPorts) {
            PortDeclarations.initialize(this);
        }
    }

    private static <T> T instantiate(Class<T> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass must not be null");
        try {
            Constructor<T> constructor = componentClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalArgumentException(
                    "Cannot access component constructor " + componentClass.getName() + "()"
                );
            }
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "Cannot create component " + componentClass.getName()
                    + ": a no-argument constructor is required",
                exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static <O> Class<O> operationsType(Class<?> componentClass) {
        Type operationsType = typeArgument(componentClass, 1);
        if (!(operationsType instanceof Class<?> operationsClass)) {
            throw new IllegalArgumentException(
                "Component " + componentClass.getName()
                    + " must directly declare a concrete runtime operations type"
            );
        }
        return operationsClass == Void.class ? null : (Class<O>) operationsClass;
    }

    private static Type typeArgument(Class<?> componentClass, int index) {
        Type genericSuperclass = componentClass.getGenericSuperclass();
        if (!(genericSuperclass instanceof ParameterizedType declaration)
            || declaration.getRawType() != AbstractComponent.class) {
            throw new IllegalArgumentException(
                "Component " + componentClass.getName()
                    + " must directly declare a concrete AbstractComponent<C, O> type"
            );
        }
        return declaration.getActualTypeArguments()[index];
    }
}
