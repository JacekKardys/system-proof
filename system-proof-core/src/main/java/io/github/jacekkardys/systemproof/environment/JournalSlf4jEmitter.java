package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.diagnostics.LogLevel;
import io.github.jacekkardys.systemproof.topology.ConnectionRef;

/** Applies logging thresholds and emits already-stored immutable entries through SLF4J. */
final class JournalSlf4jEmitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(JournalSlf4jEmitter.class);

    private final EnvironmentLogging configuration;
    private final JournalRenderer renderer;
    private final BiConsumer<LogLevel, String> sink;

    JournalSlf4jEmitter(EnvironmentLogging configuration, JournalRenderer renderer) {
        this(configuration, renderer, JournalSlf4jEmitter::emitSlf4j);
    }

    JournalSlf4jEmitter(
        EnvironmentLogging configuration,
        JournalRenderer renderer,
        BiConsumer<LogLevel, String> sink
    ) {
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration must not be null"
        );
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    void framework(JournalEntry entry, LogLevel level) {
        emit(entry, configuration.frameworkLevel(), level);
    }

    void component(JournalEntry entry, Component component, LogLevel level) {
        Objects.requireNonNull(component, "component must not be null");
        emit(entry, configuration.componentLevel(component), level);
    }

    void connection(JournalEntry entry, ConnectionRef connection, LogLevel level) {
        Objects.requireNonNull(connection, "connection must not be null");
        emit(entry, configuration.connectionLevel(connection), level);
    }

    private void emit(JournalEntry entry, LogLevel threshold, LogLevel level) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
        Objects.requireNonNull(level, "level must not be null");
        if (level == LogLevel.OFF || !threshold.includes(level)) {
            return;
        }
        renderer.renderLines(entry).forEach(line -> sink.accept(level, line));
    }

    private static void emitSlf4j(LogLevel level, String message) {
        switch (level) {
            case ERROR -> LOGGER.error(message);
            case WARN -> LOGGER.warn(message);
            case INFO -> LOGGER.info(message);
            case DEBUG -> LOGGER.debug(message);
            case TRACE -> LOGGER.trace(message);
            case OFF -> {
                // OFF entries remain stored but are never emitted.
            }
        }
    }
}
