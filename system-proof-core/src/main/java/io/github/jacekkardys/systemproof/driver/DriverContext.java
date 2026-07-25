package io.github.jacekkardys.systemproof.driver;

import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ComponentState;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/**
 * Topology, journal-backed diagnostics, and shared-resource services available only to drivers.
 *
 * <p>Each runtime context is scoped to the component whose driver received it. The journal
 * contribution capability binds that component identity and does not expose the mutable journal.
 */
public interface DriverContext {
    <T> T resolve(RequiredPort<T> required);

    <R extends AutoCloseable> R sharedResource(DriverResourceKey<R> key, Supplier<? extends R> factory);

    /**
     * Appends journal-backed diagnostic text for this driver's own component.
     *
     * @throws IllegalArgumentException if {@code component} is not the scoped driver component
     */
    void log(Component component, LogLevel level, String message);

    /** Returns the restricted journal contribution capability bound to this driver component. */
    JournalContributions journalContributions();

    String componentEvents(Component component);

    ComponentState state(Component component);
}
