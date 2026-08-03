package io.github.jacekkardys.systemproof.environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;

/** Emission thresholds for framework, component, and connection events. */
@Value
public final class EnvironmentLogging {
    @Getter(AccessLevel.NONE)
    LogLevel frameworkLevel;
    @Getter(AccessLevel.NONE)
    LogLevel defaultComponentLevel;
    @Getter(AccessLevel.NONE)
    LogLevel defaultConnectionLevel;
    @Getter(AccessLevel.NONE)
    Map<Component, LogLevel> componentLevels;
    @Getter(AccessLevel.NONE)
    Map<ConnectionId, LogLevel> connectionLevels;

    private EnvironmentLogging(
        LogLevel frameworkLevel,
        LogLevel defaultComponentLevel,
        LogLevel defaultConnectionLevel,
        Map<Component, LogLevel> componentLevels,
        Map<ConnectionId, LogLevel> connectionLevels
    ) {
        this.frameworkLevel = frameworkLevel;
        this.defaultComponentLevel = defaultComponentLevel;
        this.defaultConnectionLevel = defaultConnectionLevel;
        this.componentLevels = componentLevels;
        this.connectionLevels = connectionLevels;
    }

    /** Creates an immutable, detached logging configuration. */
    static EnvironmentLogging of(
        LogLevel frameworkLevel,
        LogLevel defaultComponentLevel,
        LogLevel defaultConnectionLevel,
        Map<Component, LogLevel> componentLevels,
        Map<ConnectionId, LogLevel> connectionLevels
    ) {
        Objects.requireNonNull(frameworkLevel, "frameworkLevel must not be null");
        Objects.requireNonNull(defaultComponentLevel, "defaultComponentLevel must not be null");
        Objects.requireNonNull(defaultConnectionLevel, "defaultConnectionLevel must not be null");
        Objects.requireNonNull(componentLevels, "componentLevels must not be null");
        Objects.requireNonNull(connectionLevels, "connectionLevels must not be null");

        IdentityHashMap<Component, LogLevel> components = new IdentityHashMap<>();
        components.putAll(componentLevels);

        return new EnvironmentLogging(
            frameworkLevel,
            defaultComponentLevel,
            defaultConnectionLevel,
            Collections.unmodifiableMap(components),
            Map.copyOf(connectionLevels)
        );
    }

    /** Starts a mutable logging configuration builder. */
    public static EnvironmentLoggingBuilder logs() {
        return new EnvironmentLoggingBuilder();
    }

    /** Returns the default INFO-level logging configuration. */
    public static EnvironmentLogging defaults() {
        return logs().build();
    }

    LogLevel frameworkLevel() {
        return frameworkLevel;
    }

    LogLevel componentLevel(Component component) {
        return componentLevels.getOrDefault(component, defaultComponentLevel);
    }

    LogLevel connectionLevel(ConnectionRef connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        return connectionLevel(connection.id());
    }

    LogLevel connectionLevel(ConnectionId connectionId) {
        return connectionLevels.getOrDefault(
            Objects.requireNonNull(connectionId, "connectionId must not be null"),
            defaultConnectionLevel
        );
    }

    void validateAgainst(EnvironmentTopology topology) {
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
