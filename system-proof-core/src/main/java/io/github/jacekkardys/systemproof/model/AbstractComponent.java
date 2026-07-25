package io.github.jacekkardys.systemproof.model;

import static io.github.jacekkardys.systemproof.configuration.ConfigurationValidator.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

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
        return ComponentMetadata.<C, O, T>analyze(componentClass)
            .materialize(qualifier, configuration, driver);
    }

    public static <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T component(
        Class<T> componentClass,
        ComponentType componentType,
        String qualifier,
        C configuration,
        ComponentDriver<C, O> driver
    ) {
        return ComponentMetadata.materialize(
            componentClass,
            componentType,
            qualifier,
            configuration,
            driver
        );
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

    final void initialize(
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
        this.operationsType = operationsType == Void.class ? null : operationsType;
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        if (materializeDeclaredPorts) {
            PortDeclarations.initialize(this);
        }
    }

}
