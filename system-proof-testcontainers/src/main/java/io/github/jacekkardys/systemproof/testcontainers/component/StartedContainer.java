package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;

/** Restricted view of one started container available to its driver hooks. */
public final class StartedContainer {
    private final Supplier<String> host;
    private final IntUnaryOperator mappedPort;
    private final BooleanSupplier running;
    private final ContainerPlan plan;

    StartedContainer(ManagedGenericContainer container, ContainerPlan plan) {
        this(
            Objects.requireNonNull(container, "container must not be null")::getHost,
            container::getMappedPort,
            container::isRunning,
            plan
        );
    }

    StartedContainer(
        Supplier<String> host,
        IntUnaryOperator mappedPort,
        BooleanSupplier running,
        ContainerPlan plan
    ) {
        this.host = Objects.requireNonNull(host, "host supplier must not be null");
        this.mappedPort = Objects.requireNonNull(
            mappedPort,
            "mapped-port function must not be null"
        );
        this.running = Objects.requireNonNull(running, "running supplier must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
    }

    public String host() {
        return host.get();
    }

    public int mappedPort(int containerPort) {
        return mappedPort.applyAsInt(containerPort);
    }

    public int mappedPort(ProvidedPort<?> port) {
        return mappedPort(plan.containerPort(port));
    }

    /** Returns whether the owned container process is still running. */
    public boolean isRunning() {
        return running.getAsBoolean();
    }

    public <T> EndpointBinding<T> binding(ProvidedPort<T> port) {
        return plan.binding(port, this);
    }

    public <T> T external(ProvidedPort<T> port) {
        return binding(port).external();
    }
}
