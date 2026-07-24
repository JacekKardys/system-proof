package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.testcontainers.diagnostics.ContainerLogConsumer;

/** Base driver that owns Testcontainers materialization while core owns lifecycle ordering. */
public abstract class TestcontainersDriver<
    C extends RuntimeConfig,
    O,
    T extends AbstractComponent<C, O>
> implements ComponentDriver<C, O> {
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
        GenericContainer<?> container = plan.container()
            .withNetwork(network)
            .withNetworkAliases(component.id().toString(), networkAlias(component))
            .withExposedPorts(plan.exposedPorts())
            .withLogConsumer(new ContainerLogConsumer(driverContext, component));

        driverContext.log(component, LogLevel.INFO, "Starting container");
        try {
            container.start();
            driverContext.log(component, LogLevel.INFO, "Container started");
            StartedContainer started = new StartedContainer(container, plan);
            O operations = createOperations(typed, started, driverContext);
            afterStart(typed, operations, started, driverContext);

            ComponentRuntime.Builder<O> runtime = ComponentRuntime.runtime(container::stop);
            plan.publishTo(runtime, started);
            if (operations != null) {
                runtime.operations(operations);
            }
            return runtime.build();
        } catch (RuntimeException | Error failure) {
            driverContext.log(
                component,
                LogLevel.ERROR,
                "Container start failed: " + failure.getClass().getSimpleName() + messageSuffix(failure)
            );
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

    public static String networkAlias(Component component) {
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

    private static String messageSuffix(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? ""
            : " - " + failure.getMessage();
    }
}
