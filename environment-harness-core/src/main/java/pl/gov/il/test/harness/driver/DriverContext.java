package pl.gov.il.test.harness.driver;

import java.util.function.Supplier;
import pl.gov.il.test.harness.model.Component;
import pl.gov.il.test.harness.model.ComponentState;
import pl.gov.il.test.harness.model.LogLevel;
import pl.gov.il.test.harness.model.RequiredPort;

/** Topology, logging, and shared-resource services available only to drivers. */
public interface DriverContext {
    <T> T resolve(RequiredPort<T> required);

    <R extends AutoCloseable> R sharedResource(DriverResourceKey<R> key, Supplier<? extends R> factory);

    void log(Component component, LogLevel level, String message);

    String componentEvents(Component component);

    ComponentState state(Component component);
}
