package io.github.jacekkardys.systemproof.api;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.model.component.Component;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionRef;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.model.logging.LogLevel;

/** Emission thresholds for framework, component, and connection events. */
public final class EnvironmentLogging {
    private final LogLevel frameworkLevel;
    private final LogLevel defaultComponentLevel;
    private final LogLevel defaultConnectionLevel;
    private final Map<Component, LogLevel> componentLevels;
    private final Map<ConnectionId, LogLevel> connectionLevels;

    EnvironmentLogging(LogLevel frameworkLevel, LogLevel defaultComponentLevel, LogLevel defaultConnectionLevel,
        Map<Component, LogLevel> componentLevels, Map<ConnectionId, LogLevel> connectionLevels) {
        this.frameworkLevel = frameworkLevel;
        this.defaultComponentLevel = defaultComponentLevel;
        this.defaultConnectionLevel = defaultConnectionLevel;
        IdentityHashMap<Component, LogLevel> components = new IdentityHashMap<>();
        components.putAll(componentLevels);
        this.componentLevels = Collections.unmodifiableMap(components);
        this.connectionLevels = Map.copyOf(connectionLevels);
    }

    /** Starts a mutable logging configuration builder. */
    public static EnvironmentLoggingBuilder logs() {
        return new EnvironmentLoggingBuilder();
    }

    /** Returns the default INFO-level logging configuration. */
    public static EnvironmentLogging defaults() {
        return logs().build();
    }

    public LogLevel frameworkLevel() {
        return frameworkLevel;
    }

    public LogLevel componentLevel(Component component) {
        return componentLevels.getOrDefault(component, defaultComponentLevel);
    }

    public LogLevel connectionLevel(ConnectionRef connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        return connectionLevel(connection.id());
    }

    public LogLevel connectionLevel(ConnectionId connectionId) {
        return connectionLevels.getOrDefault(
            Objects.requireNonNull(connectionId, "connectionId must not be null"),
            defaultConnectionLevel
        );
    }

    public void validateAgainst(EnvironmentTopology topology) {
        componentLevels.keySet().stream()
            .filter(component -> !topology.contains(component))
            .findFirst()
            .ifPresent(component -> {
                throw new IllegalArgumentException(
                    "Logging configuration references component '" + component.id() + "' outside the environment"
                );
            });
        connectionLevels.keySet().stream()
            .filter(id -> topology.connections().stream().noneMatch(connection -> connection.id().equals(id)))
            .findFirst()
            .ifPresent(id -> {
                throw new IllegalArgumentException(
                    "Logging configuration references connection '" + id + "' outside the environment"
                );
            });
    }

}
