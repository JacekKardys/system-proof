package io.github.jacekkardys.systemproof.driver;

import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Topology, logging, and shared-resource services available only to drivers. */
public interface DriverContext {
    <T> T resolve(RequiredPort<T> required);

    <R extends AutoCloseable> R sharedResource(DriverResourceKey<R> key, Supplier<? extends R> factory);

    void log(Component component, LogLevel level, String message);

    String componentEvents(Component component);

    ComponentState state(Component component);
}
