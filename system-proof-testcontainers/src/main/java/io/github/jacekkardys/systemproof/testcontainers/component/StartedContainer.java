package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;

/** Restricted view of one started container available to its driver hooks. */
public final class StartedContainer {
    private final GenericContainer<?> container;
    private final ContainerPlan plan;

    StartedContainer(GenericContainer<?> container, ContainerPlan plan) {
        this.container = Objects.requireNonNull(container, "container must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
    }

    public String host() {
        return container.getHost();
    }

    public int mappedPort(int containerPort) {
        return container.getMappedPort(containerPort);
    }

    public int mappedPort(ProvidedPort<?> port) {
        return mappedPort(plan.containerPort(port));
    }

    public <T> EndpointBinding<T> binding(ProvidedPort<T> port) {
        return plan.binding(port, this);
    }

    public <T> T external(ProvidedPort<T> port) {
        return binding(port).external();
    }
}
