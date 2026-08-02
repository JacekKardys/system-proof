package io.github.jacekkardys.systemproof.api;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.logging.LogLevel;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/** Mutable builder for one immutable {@link EnvironmentLogging} configuration. */
public final class EnvironmentLoggingBuilder {
    private LogLevel frameworkLevel = LogLevel.INFO;
    private LogLevel defaultComponentLevel = LogLevel.INFO;
    private LogLevel defaultConnectionLevel = LogLevel.INFO;
    private final IdentityHashMap<Component, LogLevel> componentLevels = new IdentityHashMap<>();
    private final Map<ConnectionId, LogLevel> connectionLevels = new LinkedHashMap<>();

    /** Sets the minimum framework-event level. */
    public EnvironmentLoggingBuilder frameworkLevel(LogLevel level) {
        frameworkLevel = Objects.requireNonNull(level, "level must not be null");
        return this;
    }

    /** Sets the default minimum component-event level. */
    public EnvironmentLoggingBuilder defaultComponentLevel(LogLevel level) {
        defaultComponentLevel = Objects.requireNonNull(level, "level must not be null");
        return this;
    }

    /** Sets the default minimum connection-event level. */
    public EnvironmentLoggingBuilder defaultConnectionLevel(LogLevel level) {
        defaultConnectionLevel = Objects.requireNonNull(level, "level must not be null");
        return this;
    }

    /** Uses {@link LogLevel#WARN} as the default component-event threshold. */
    public EnvironmentLoggingBuilder warnByDefault() {
        return defaultComponentLevel(LogLevel.WARN);
    }

    /** Overrides the event threshold for the exact supplied component instance. */
    public EnvironmentLoggingBuilder componentLevel(Component component, LogLevel level) {
        componentLevels.put(
            Objects.requireNonNull(component, "component must not be null"),
            Objects.requireNonNull(level, "level must not be null")
        );
        return this;
    }

    /** Enables {@link LogLevel#INFO} events for the supplied component instances. */
    public EnvironmentLoggingBuilder info(Component... components) {
        for (Component component : components) {
            componentLevel(component, LogLevel.INFO);
        }
        return this;
    }

    /** Overrides the event threshold for one deterministic logical connection. */
    public <C> EnvironmentLoggingBuilder connectionLevel(RequiredPort<C> from, ProvidedPort<C> to, LogLevel level) {
        connectionLevels.put(
            ConnectionId.between(from, to),
            Objects.requireNonNull(level, "level must not be null")
        );
        return this;
    }

    /** Creates an immutable detached logging configuration. */
    public EnvironmentLogging build() {
        return new EnvironmentLogging(frameworkLevel, defaultComponentLevel, defaultConnectionLevel,
            componentLevels, connectionLevels);
    }
}
