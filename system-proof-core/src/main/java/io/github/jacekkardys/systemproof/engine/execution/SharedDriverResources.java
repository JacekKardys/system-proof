package io.github.jacekkardys.systemproof.engine.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;

/** Owns environment-scoped driver resources and their reverse-order cleanup lifecycle. */
final class SharedDriverResources {
    private final IdentityHashMap<DriverResourceKey<?>, AutoCloseable> resources =
        new IdentityHashMap<>();
    private final List<SharedResource> creationOrder = new ArrayList<>();
    private final EnvironmentEventLog eventLog;

    SharedDriverResources(EnvironmentEventLog eventLog) {
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");
    }

    synchronized <R extends AutoCloseable> R getOrCreate(
        DriverResourceKey<R> key,
        Supplier<? extends R> factory
    ) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        AutoCloseable existing = resources.get(key);
        if (existing != null) {
            return key.cast(existing);
        }
        R resource = Objects.requireNonNull(factory.get(), "shared resource factory returned null");
        resources.put(key, resource);
        creationOrder.add(new SharedResource(key.name(), resource));
        return resource;
    }

    synchronized Throwable close() {
        Throwable firstFailure = null;
        List<SharedResource> reverse = new ArrayList<>(creationOrder);
        Collections.reverse(reverse);
        for (SharedResource resource : reverse) {
            try {
                resource.value().close();
            } catch (Exception | Error failure) {
                eventLog.driverResourceCleanupFailure(resource.name(), failure);
                firstFailure = EnvironmentRuntimeFailures.accumulate(firstFailure, failure);
            }
        }
        resources.clear();
        creationOrder.clear();
        return firstFailure;
    }

    private record SharedResource(String name, AutoCloseable value) {}
}
