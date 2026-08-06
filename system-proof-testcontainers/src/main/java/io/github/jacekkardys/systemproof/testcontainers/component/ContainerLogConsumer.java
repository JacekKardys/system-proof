package io.github.jacekkardys.systemproof.testcontainers.component;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.output.OutputFrame;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.journal.LogLevel;

/** Sends container output into the environment's structured diagnostic journal. */
final class ContainerLogConsumer implements Consumer<OutputFrame> {
    private static final Pattern LEVEL_MARKER = Pattern.compile(
        "(?i)(?:\\\"(?:level|severity)\\\"\\s*:\\s*\\\"|(?:^|[\\s\\[(:]))"
            + "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|NOTICE|LOG)"
            + "(?:\\\"|$|[\\s\\]):])"
    );

    private final DriverContext context;
    private final Component component;
    private final UnaryOperator<String> sanitizer;

    ContainerLogConsumer(DriverContext context, Component component) {
        this(context, component, UnaryOperator.identity());
    }

    ContainerLogConsumer(
        DriverContext context,
        Component component,
        UnaryOperator<String> sanitizer
    ) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.component = Objects.requireNonNull(component, "component must not be null");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
    }

    @Override
    public void accept(OutputFrame frame) {
        if (frame == null || frame.getType() == OutputFrame.OutputType.END) {
            return;
        }
        String output = frame.getUtf8String();
        if (output == null || output.isBlank()) {
            return;
        }
        LogLevel level = detectLevel(frame.getType(), output);
        String sanitized = Objects.requireNonNull(
            sanitizer.apply(output.stripTrailing()),
            "container log sanitizer must not return null"
        );
        if (!sanitized.isBlank()) {
            context.log(component, level, sanitized);
        }
    }

    static LogLevel detectLevel(OutputFrame.OutputType outputType, String output) {
        Matcher marker = LEVEL_MARKER.matcher(output);
        if (marker.find()) {
            return switch (marker.group(1).toUpperCase(Locale.ROOT)) {
                case "TRACE" -> LogLevel.TRACE;
                case "DEBUG" -> LogLevel.DEBUG;
                case "WARN", "WARNING" -> LogLevel.WARN;
                case "ERROR", "FATAL" -> LogLevel.ERROR;
                default -> LogLevel.INFO;
            };
        }
        return outputType == OutputFrame.OutputType.STDERR ? LogLevel.ERROR : LogLevel.INFO;
    }
}
