package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Objects;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

/** Concrete composition driver for standard containers without custom lifecycle behavior. */
public final class ContainerDriver<
    C extends RuntimeConfig,
    O,
    T extends AbstractComponent<C, O>
> extends TestcontainersDriver<C, O, T> {
    private final PlanFactory<T> planFactory;
    private final OperationsFactory<T, O> operationsFactory;

    public ContainerDriver(
        Class<T> componentType,
        PlanFactory<T> planFactory,
        OperationsFactory<T, O> operationsFactory
    ) {
        super(componentType);
        this.planFactory = Objects.requireNonNull(planFactory, "planFactory must not be null");
        this.operationsFactory = Objects.requireNonNull(
            operationsFactory,
            "operationsFactory must not be null"
        );
    }

    public static <
        C extends RuntimeConfig,
        T extends AbstractComponent<C, Void>
    > ContainerDriver<C, Void, T> container(
        Class<T> componentType,
        PlanFactory<T> planFactory
    ) {
        return new ContainerDriver<>(componentType, planFactory, (component, container, context) -> null);
    }

    @Override
    protected ContainerPlan create(T component, DriverContext context) {
        return planFactory.create(component, context);
    }

    @Override
    protected O createOperations(
        T component,
        StartedContainer container,
        DriverContext context
    ) {
        return operationsFactory.create(component, container, context);
    }

    @FunctionalInterface
    public interface PlanFactory<T> {
        ContainerPlan create(T component, DriverContext context);
    }

    @FunctionalInterface
    public interface OperationsFactory<T, O> {
        O create(T component, StartedContainer container, DriverContext context);
    }
}
