package io.github.jacekkardys.systemproof.api;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.Connection;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.LogLevel;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;

/** Emission thresholds for framework, component, and connection events. */
public final class EnvironmentLogging {
    private final LogLevel frameworkLevel;
    private final LogLevel defaultComponentLevel;
    private final LogLevel defaultConnectionLevel;
    private final Map<Component, LogLevel> componentLevels;
    private final Map<String, LogLevel> connectionLevels;

    private EnvironmentLogging(Builder builder) {
        frameworkLevel = builder.frameworkLevel;
        defaultComponentLevel = builder.defaultComponentLevel;
        defaultConnectionLevel = builder.defaultConnectionLevel;
        IdentityHashMap<Component, LogLevel> components = new IdentityHashMap<>();
        components.putAll(builder.componentLevels);
        componentLevels = Collections.unmodifiableMap(components);
        connectionLevels = Map.copyOf(builder.connectionLevels);
    }

    public static Builder logs() {
        return new Builder();
    }

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
        return connectionLevels.getOrDefault(connection.id(), defaultConnectionLevel);
    }

    public void validateAgainst(Environment environment) {
        componentLevels.keySet().stream()
            .filter(component -> !environment.contains(component))
            .findFirst()
            .ifPresent(component -> {
                throw new IllegalArgumentException(
                    "Logging configuration references component '" + component.id() + "' outside the environment"
                );
            });
        connectionLevels.keySet().stream()
            .filter(id -> environment.connections().stream().noneMatch(connection -> connection.id().equals(id)))
            .findFirst()
            .ifPresent(id -> {
                throw new IllegalArgumentException(
                    "Logging configuration references connection '" + id + "' outside the environment"
                );
            });
    }

    public static final class Builder {
        private LogLevel frameworkLevel = LogLevel.INFO;
        private LogLevel defaultComponentLevel = LogLevel.INFO;
        private LogLevel defaultConnectionLevel = LogLevel.INFO;
        private final IdentityHashMap<Component, LogLevel> componentLevels = new IdentityHashMap<>();
        private final Map<String, LogLevel> connectionLevels = new LinkedHashMap<>();

        private Builder() {}

        public Builder frameworkLevel(LogLevel level) {
            frameworkLevel = Objects.requireNonNull(level, "level must not be null");
            return this;
        }

        public Builder defaultComponentLevel(LogLevel level) {
            defaultComponentLevel = Objects.requireNonNull(level, "level must not be null");
            return this;
        }

        public Builder defaultConnectionLevel(LogLevel level) {
            defaultConnectionLevel = Objects.requireNonNull(level, "level must not be null");
            return this;
        }

        public Builder warnByDefault() {
            return defaultComponentLevel(LogLevel.WARN);
        }

        public Builder componentLevel(Component component, LogLevel level) {
            componentLevels.put(
                Objects.requireNonNull(component, "component must not be null"),
                Objects.requireNonNull(level, "level must not be null")
            );
            return this;
        }

        public Builder info(Component... components) {
            for (Component component : components) {
                componentLevel(component, LogLevel.INFO);
            }
            return this;
        }

        public <C> Builder connectionLevel(
            RequiredPort<C> from,
            ProvidedPort<C> to,
            LogLevel level
        ) {
            connectionLevels.put(
                Connection.connect(from, to).id(),
                Objects.requireNonNull(level, "level must not be null")
            );
            return this;
        }

        public EnvironmentLogging build() {
            return new EnvironmentLogging(this);
        }
    }
}
