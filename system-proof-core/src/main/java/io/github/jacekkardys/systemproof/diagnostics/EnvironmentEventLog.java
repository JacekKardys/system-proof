package io.github.jacekkardys.systemproof.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.ConnectionRef;
import io.github.jacekkardys.systemproof.model.LogLevel;

/** One monotonic T+ timeline shared by framework, driver, connection, and component events. */
public final class EnvironmentEventLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentEventLog.class);

    private final EnvironmentLogging configuration;
    private final LongSupplier nanoTime;
    private final long startedAt;
    private final List<Event> events = new ArrayList<>();

    public EnvironmentEventLog(EnvironmentLogging configuration) {
        this(configuration, System::nanoTime);
    }

    EnvironmentEventLog(EnvironmentLogging configuration, LongSupplier nanoTime) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        startedAt = nanoTime.getAsLong();
    }

    public void framework(LogLevel level, String message) {
        append(configuration.frameworkLevel(), level, "[FRAMEWORK] [environment]", null, message);
    }

    public void connection(ConnectionRef connection, LogLevel level, String message) {
        append(
            configuration.connectionLevel(connection),
            level,
            "[CONNECTION] [" + connection.id() + "]",
            null,
            message
        );
    }

    public void component(Component component, LogLevel level, String message) {
        append(
            configuration.componentLevel(component),
            level,
            "[COMPONENT] [" + component.id() + "]",
            component,
            message
        );
    }

    public synchronized EnvironmentDiagnostics snapshot() {
        return EnvironmentDiagnostics.diagnostics(render(events));
    }

    public synchronized String componentSnapshot(Component component) {
        return render(events.stream().filter(event -> event.component() == component).toList());
    }

    private synchronized void append(
        LogLevel threshold,
        LogLevel level,
        String labels,
        Component component,
        String message
    ) {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");
        message.lines().forEach(line -> {
            String rendered = timestamp() + " " + labels + " " + line;
            events.add(new Event(rendered, component));
            if (threshold.includes(level)) {
                emit(level, rendered);
            }
        });
    }

    private String timestamp() {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - startedAt);
        long hours = elapsedMillis / TimeUnit.HOURS.toMillis(1);
        long minutes = elapsedMillis / TimeUnit.MINUTES.toMillis(1) % 60;
        long seconds = elapsedMillis / TimeUnit.SECONDS.toMillis(1) % 60;
        long millis = elapsedMillis % 1_000;
        return String.format(Locale.ROOT, "T+%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private static String render(List<Event> selected) {
        return selected.stream().map(Event::rendered)
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("");
    }

    private static void emit(LogLevel level, String message) {
        switch (level) {
            case ERROR -> LOGGER.error(message);
            case WARN -> LOGGER.warn(message);
            case INFO -> LOGGER.info(message);
            case DEBUG -> LOGGER.debug(message);
            case TRACE -> LOGGER.trace(message);
            case OFF -> {
                // OFF events are filtered before emission.
            }
        }
    }

    private record Event(String rendered, Component component) {}
}
