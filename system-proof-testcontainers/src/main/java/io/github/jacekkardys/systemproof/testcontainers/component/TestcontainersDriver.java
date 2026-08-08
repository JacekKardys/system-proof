package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Objects;
import org.testcontainers.containers.Network;
import io.github.jacekkardys.systemproof.driver.ComponentBoundDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;

/**
 * Base driver that owns a restricted Testcontainers lifecycle; core owns lifecycle ordering.
 */
public abstract class TestcontainersDriver<
    C extends RuntimeConfig,
    O,
    T extends AbstractComponent<C, O>
> implements ComponentBoundDriver<C, O, T> {
    private static final DriverResourceKey<Network> NETWORK =
        DriverResourceKey.resourceKey("testcontainers-network", Network.class);

    private final Class<T> componentType;

    protected TestcontainersDriver(Class<T> componentType) {
        this.componentType = Objects.requireNonNull(componentType, "componentType must not be null");
    }

    @Override
    public final ComponentRuntime<O> start(
        AbstractComponent<C, O> component,
        DriverContext driverContext
    ) {
        T typed = cast(component);
        ContainerPlan plan = Objects.requireNonNull(
            create(typed, driverContext),
            "Driver for component '" + component.id() + "' returned null container plan"
        );
        plan.validateFor(component);
        Network network = driverContext.sharedResource(NETWORK, Network::newNetwork);
        ManagedGenericContainer container = plan.container()
            .withNetwork(network)
            .withNetworkAliases(component.id().toString(), networkAlias(component))
            .withExposedPorts(plan.exposedPorts());

        try {
            container.start();
            StartedContainer started = new StartedContainer(container, plan);
            plan.awaitReadiness(started);
            O operations = createOperations(typed, started, driverContext);
            afterStart(typed, operations, started, driverContext);

            ComponentRuntime.Builder<O> runtime = ComponentRuntime.runtime(container::stop);
            plan.publishTo(runtime, started);
            if (operations != null) {
                runtime.operations(operations);
            }
            return runtime.build();
        } catch (RuntimeException | Error failure) {
            if (container.isCreated()) {
                try {
                    container.stop();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    protected abstract ContainerPlan create(T component, DriverContext context);

    protected O createOperations(T component, StartedContainer container, DriverContext context) {
        return null;
    }

    protected void afterStart(
        T component,
        O operations,
        StartedContainer container,
        DriverContext context
    ) {}

    static String networkAlias(Component component) {
        return component.id() + ".test";
    }

    private T cast(Component component) {
        if (!componentType.isInstance(component)) {
            throw new IllegalArgumentException(
                "Driver for " + componentType.getName() + " cannot start component '" + component.id()
                    + "' of type " + component.getClass().getName()
            );
        }
        return componentType.cast(component);
    }

}
